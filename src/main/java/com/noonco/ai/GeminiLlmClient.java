package com.noonco.ai;

import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.StreamSupport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.errors.ApiException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Part;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "gemini")
public class GeminiLlmClient implements LlmClient {

	// 모델 과부하. 이 응답일 때만 예비 모델로 폴백한다 (429 할당량 초과 등은 같은 키라 폴백해도 소용없다)
	private static final int OVERLOADED = 503;

	private final Client client;
	private final String model;
	private final String fallbackModel;
	private final GenerateContentConfig config;
	// 가상 스레드는 인터럽트 시 블로킹 소켓 I/O 도 끊기므로, close 로 대기 중인 요청을 바로 중단할 수 있다.
	private final ExecutorService requestExecutor = Executors.newVirtualThreadPerTaskExecutor();

	public GeminiLlmClient(LlmProperties properties) {
		LlmProperties.Gemini gemini = properties.gemini();
		if (!StringUtils.hasText(gemini.apiKey())) {
			throw new IllegalStateException("llm.provider=gemini 이지만 GEMINI_API_KEY 가 설정되지 않았습니다.");
		}
		this.client = Client.builder()
				.apiKey(gemini.apiKey())
				.httpOptions(HttpOptions.builder()
						// OkHttp callTimeout 으로 적용된다: 연결·재시도·스트림 수신 전체에 걸리는 시간 제한
						.timeout(Math.toIntExact(gemini.timeout().toMillis()))
						.retryOptions(HttpRetryOptions.builder()
								// 첫 시도를 포함한 총 시도 횟수
								.attempts(gemini.retryAttempts())
								.build())
						.build())
				.build();
		this.model = gemini.model();
		this.fallbackModel = gemini.fallbackModel();
		this.config = GenerateContentConfig.builder()
				.temperature(gemini.temperature())
				.maxOutputTokens(gemini.maxOutputTokens())
				.build();
		log.info("Gemini LLM 클라이언트 초기화: model={}, fallbackModel={}, temperature={}, maxOutputTokens={}, timeout={}, "
				+ "retryAttempts={}", model, fallbackModel, gemini.temperature(), gemini.maxOutputTokens(), gemini.timeout(),
				gemini.retryAttempts());
	}

	/**
	 * 요청은 별도 가상 스레드에서 시작되고, 스트림은 즉시 반환된다.
	 * 첫 응답을 기다리는 동안에도 close 하면 요청이 취소된다.
	 */
	@Override
	public LlmStream stream(LlmRequest request) {
		Content content = toContent(request);
		return openStream(requestExecutor, model, fallbackModel,
				requestModel -> new GeminiResponse(requestModel,
						client.models.generateContentStream(requestModel, content, config)),
				response -> textChunks(response.stream()));
	}

	/**
	 * 폴백은 요청 단계(응답 스트림을 받기 전)에서만 한다. 조각을 받기 시작한 뒤의 실패는 순회 중에 나므로
	 * 다시 시도하지 않는다(이미 보낸 해설과 중복되지 않게).
	 */
	static <R extends AsyncLlmStream.ModelResponse> LlmStream openStream(ExecutorService executor, String model,
			String fallbackModel, Function<String, R> request, Function<R, Iterator<String>> reader) {
		return new AsyncLlmStream<>(executor, () -> requestWithFallback(model, fallbackModel, request), reader);
	}

	/**
	 * 기본 모델이 503(과부하)이면 같은 API 키로 예비 모델에 1회 더 요청한다. SDK 재시도를 모두 쓴 뒤의 실패다.
	 * 예비 모델이 비어 있거나 기본 모델과 같으면, 또는 그 사이 요청이 취소(인터럽트)됐으면 폴백하지 않는다.
	 */
	static <R> R requestWithFallback(String model, String fallbackModel, Function<String, R> request) {
		try {
			return request.apply(model);
		} catch (ApiException e) {
			if (e.code() != OVERLOADED || !StringUtils.hasText(fallbackModel) || fallbackModel.equals(model)
					|| Thread.currentThread().isInterrupted()) {
				throw e;
			}
			// API 키는 요청 헤더로만 보내므로 응답 오류(code·status·message)에는 들어 있지 않다
			log.warn("Gemini 기본 모델 과부하로 예비 모델로 재시도합니다: model={}, fallbackModel={}, reason={} {} {}", model,
					fallbackModel, e.code(), e.status(), e.message());
			try {
				return request.apply(fallbackModel);
			} catch (RuntimeException fallbackFailure) {
				fallbackFailure.addSuppressed(e);
				throw fallbackFailure;
			}
		}
	}

	@Override
	public String model() {
		return model;
	}

	@PreDestroy
	public void shutdown() {
		requestExecutor.shutdownNow();
		client.close();
	}

	/**
	 * 이미지·PDF 모두 파일 바이트를 받은 mimeType 그대로 inline Part 로 보낸다.
	 */
	static Content toContent(LlmRequest request) {
		return switch (request.input()) {
			case LlmInput.File file -> Content.fromParts(
					Part.fromBytes(file.bytes(), file.mimeType()),
					Part.fromText(request.prompt()));
		};
	}

	/**
	 * 응답 스트림과, 그 응답을 보낸 모델.
	 */
	record GeminiResponse(String model, ResponseStream<GenerateContentResponse> stream)
			implements AsyncLlmStream.ModelResponse {

		@Override
		public void close() {
			stream.close();
		}
	}

	/**
	 * 사고(thought) 조각이나 종료 신호만 담긴 응답처럼 텍스트가 없는 조각은 건너뛴다.
	 */
	private static Iterator<String> textChunks(ResponseStream<GenerateContentResponse> responseStream) {
		return StreamSupport.stream(responseStream.spliterator(), false)
				.map(GenerateContentResponse::text)
				.filter(StringUtils::hasLength)
				.iterator();
	}
}
