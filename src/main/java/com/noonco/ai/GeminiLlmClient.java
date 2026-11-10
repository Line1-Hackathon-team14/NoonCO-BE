package com.noonco.ai;

import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.StreamSupport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.google.genai.Client;
import com.google.genai.ResponseStream;
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

	private final Client client;
	private final String model;
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
		this.config = GenerateContentConfig.builder()
				.temperature(gemini.temperature())
				.maxOutputTokens(gemini.maxOutputTokens())
				.build();
		log.info("Gemini LLM 클라이언트 초기화: model={}, temperature={}, maxOutputTokens={}, timeout={}, retryAttempts={}",
				model, gemini.temperature(), gemini.maxOutputTokens(), gemini.timeout(), gemini.retryAttempts());
	}

	/**
	 * 요청은 별도 가상 스레드에서 시작되고, 스트림은 즉시 반환된다.
	 * 첫 응답을 기다리는 동안에도 close 하면 요청이 취소된다.
	 */
	@Override
	public LlmStream stream(LlmRequest request) {
		Content content = toContent(request);
		return new AsyncLlmStream<>(
				requestExecutor,
				() -> client.models.generateContentStream(model, content, config),
				GeminiLlmClient::textChunks);
	}

	@PreDestroy
	public void shutdown() {
		requestExecutor.shutdownNow();
		client.close();
	}

	private Content toContent(LlmRequest request) {
		return switch (request.input()) {
			case LlmInput.Image image -> Content.fromParts(
					Part.fromBytes(image.bytes(), image.mimeType()),
					Part.fromText(request.prompt()));
			case LlmInput.Text text -> Content.fromParts(
					Part.fromText(request.prompt()),
					Part.fromText(text.content()));
		};
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
