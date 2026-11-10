package com.noonco.ai;

import java.util.Iterator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
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

	public GeminiLlmClient(LlmProperties properties) {
		LlmProperties.Gemini gemini = properties.gemini();
		if (!StringUtils.hasText(gemini.apiKey())) {
			throw new IllegalStateException("llm.provider=gemini 이지만 GEMINI_API_KEY 가 설정되지 않았습니다.");
		}
		this.client = Client.builder()
				.apiKey(gemini.apiKey())
				.build();
		this.model = gemini.model();
		this.config = GenerateContentConfig.builder()
				.temperature(gemini.temperature())
				.maxOutputTokens(gemini.maxOutputTokens())
				.build();
		log.info("Gemini LLM 클라이언트 초기화: model={}, temperature={}, maxOutputTokens={}",
				model, gemini.temperature(), gemini.maxOutputTokens());
	}

	@Override
	public LlmStream stream(LlmRequest request) {
		ResponseStream<GenerateContentResponse> responseStream =
				client.models.generateContentStream(model, toContent(request), config);
		return new GeminiLlmStream(responseStream);
	}

	@PreDestroy
	public void shutdown() {
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

	private static class GeminiLlmStream extends AbstractLlmStream {

		private final ResponseStream<GenerateContentResponse> responseStream;
		private final Iterator<GenerateContentResponse> responses;

		GeminiLlmStream(ResponseStream<GenerateContentResponse> responseStream) {
			this.responseStream = responseStream;
			this.responses = responseStream.iterator();
		}

		@Override
		protected String fetchNext() {
			// 사고(thought) 조각이나 종료 신호만 담긴 응답처럼 텍스트가 없는 조각은 건너뛴다.
			while (responses.hasNext()) {
				String text = responses.next().text();
				if (text != null && !text.isEmpty()) {
					return text;
				}
			}
			return null;
		}

		@Override
		protected void onClose() {
			responseStream.close();
		}
	}
}
