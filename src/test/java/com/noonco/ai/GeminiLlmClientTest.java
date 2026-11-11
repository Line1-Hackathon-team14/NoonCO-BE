package com.noonco.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.ServerException;
import com.google.genai.types.Blob;
import com.google.genai.types.BlockedReason;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponsePromptFeedback;
import com.google.genai.types.HarmCategory;
import com.google.genai.types.HarmProbability;
import com.google.genai.types.Part;
import com.google.genai.types.SafetyRating;

/**
 * API 를 호출하지 않고 요청 Content 구성과 예비 모델 폴백을 확인한다.
 * 폴백은 SDK 대신 모델 이름별 응답을 흉내 낸 요청 함수로 검증한다.
 */
class GeminiLlmClientTest {

	private static final byte[] PDF_BYTES = "%PDF-1.7\n".getBytes();
	private static final byte[] PNG_BYTES = {(byte)0x89, 'P', 'N', 'G'};
	private static final String PRIMARY = "gemini-3.8-flash";
	private static final String FALLBACK = "gemini-3.5-flash-lite";

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	// 요청한 모델 이름 순서
	private final List<String> requested = Collections.synchronizedList(new ArrayList<>());

	@AfterEach
	void tearDown() {
		executor.shutdownNow();
	}

	@Test
	void overloadedPrimaryFallsBackToFallbackModelAndReportsIt() {
		Function<String, TestResponse> request = model -> {
			requested.add(model);
			if (model.equals(PRIMARY)) {
				throw overloaded();
			}
			return new TestResponse(model, List.of("예비 ", "모델 응답"), null);
		};

		try (LlmStream stream = open(FALLBACK, request)) {
			assertThat(stream).containsExactly("예비 ", "모델 응답");
			// analysis.model 에는 실제로 응답한 예비 모델이 저장된다
			assertThat(stream.model()).isEqualTo(FALLBACK);
		}
		assertThat(requested).containsExactly(PRIMARY, FALLBACK);
	}

	@Test
	void primaryResponseReportsPrimaryModel() {
		try (LlmStream stream = open(FALLBACK, model -> {
			requested.add(model);
			return new TestResponse(model, List.of("기본 모델 응답"), null);
		})) {
			assertThat(stream).containsExactly("기본 모델 응답");
			assertThat(stream.model()).isEqualTo(PRIMARY);
		}
		assertThat(requested).containsExactly(PRIMARY);
	}

	@Test
	void failureAfterChunksIsNotRetried() {
		ServerException midStream = overloaded();
		Function<String, TestResponse> request = model -> {
			requested.add(model);
			return new TestResponse(model, List.of("첫 조각"), midStream);
		};

		List<String> received = new ArrayList<>();
		try (LlmStream stream = open(FALLBACK, request)) {
			assertThatThrownBy(() -> stream.forEach(received::add)).isSameAs(midStream);
		}
		// 이미 받은 조각과 해설이 겹치지 않도록 예비 모델로 다시 요청하지 않는다
		assertThat(received).containsExactly("첫 조각");
		assertThat(requested).containsExactly(PRIMARY);
	}

	@Test
	void quotaExceededIsNotRetried() {
		ClientException quota = new ClientException(429, "RESOURCE_EXHAUSTED", "Quota exceeded");
		Function<String, TestResponse> request = model -> {
			requested.add(model);
			throw quota;
		};

		try (LlmStream stream = open(FALLBACK, request)) {
			assertThatThrownBy(() -> stream.iterator().hasNext()).isSameAs(quota);
		}
		assertThat(requested).containsExactly(PRIMARY);
	}

	@Test
	void emptyOrSameFallbackModelDisablesFallback() {
		for (String fallback : new String[] {"", " ", null, PRIMARY}) {
			requested.clear();
			ServerException failure = overloaded();
			assertThatThrownBy(() -> GeminiLlmClient.requestWithFallback(PRIMARY, fallback, model -> {
				requested.add(model);
				throw failure;
			})).isSameAs(failure);
			assertThat(requested).as("fallbackModel=%s", fallback).containsExactly(PRIMARY);
		}
	}

	@Test
	void fallbackFailureKeepsPrimaryFailureAsSuppressed() {
		ServerException primaryFailure = overloaded();
		ApiException fallbackFailure = new ServerException(500, "INTERNAL", "Internal error");

		assertThatThrownBy(() -> GeminiLlmClient.requestWithFallback(PRIMARY, FALLBACK, model -> {
			requested.add(model);
			throw model.equals(PRIMARY) ? primaryFailure : fallbackFailure;
		})).isSameAs(fallbackFailure).satisfies(e -> assertThat(e.getSuppressed()).containsExactly(primaryFailure));
		assertThat(requested).containsExactly(PRIMARY, FALLBACK);
	}

	@Test
	void pdfInputBecomesApplicationPdfPartFollowedByPrompt() {
		List<Part> parts = parts(LlmRequest.ofFile("프롬프트", PDF_BYTES, "application/pdf"));

		assertThat(parts).hasSize(2);
		Blob blob = parts.get(0).inlineData().orElseThrow();
		assertThat(blob.mimeType()).contains("application/pdf");
		assertThat(blob.data()).hasValue(PDF_BYTES);
		assertThat(parts.get(1).text()).contains("프롬프트");
	}

	@Test
	void imageInputKeepsImageMimeType() {
		List<Part> parts = parts(LlmRequest.ofFile("프롬프트", PNG_BYTES, "image/png"));

		Blob blob = parts.get(0).inlineData().orElseThrow();
		assertThat(blob.mimeType()).contains("image/png");
		assertThat(blob.data()).hasValue(PNG_BYTES);
	}

	private static List<Part> parts(LlmRequest request) {
		Content content = GeminiLlmClient.toContent(request);
		return content.parts().orElseThrow();
	}

	@Test
	void describeFinishReportsFinishReasonAndBlockedSafetyOnly() {
		GenerateContentResponse response = GenerateContentResponse.builder()
				.candidates(List.of(Candidate.builder()
						.finishReason(FinishReason.Known.SAFETY)
						.safetyRatings(List.of(
								SafetyRating.builder().category(HarmCategory.Known.HARM_CATEGORY_DANGEROUS_CONTENT)
										.probability(HarmProbability.Known.HIGH).blocked(true).build(),
								SafetyRating.builder().category(HarmCategory.Known.HARM_CATEGORY_HARASSMENT)
										.probability(HarmProbability.Known.NEGLIGIBLE).build()))
						.build()))
				.build();

		assertThat(GeminiLlmClient.describeFinish(response))
				.isEqualTo("finishReason=SAFETY, blockedSafety=[HARM_CATEGORY_DANGEROUS_CONTENT(HIGH)]");
	}

	@Test
	void describeFinishReportsPromptBlock() {
		GenerateContentResponse response = GenerateContentResponse.builder()
				.promptFeedback(GenerateContentResponsePromptFeedback.builder()
						.blockReason(BlockedReason.Known.PROHIBITED_CONTENT)
						.blockReasonMessage("blocked"))
				.build();

		assertThat(GeminiLlmClient.describeFinish(response))
				.isEqualTo("blockReason=PROHIBITED_CONTENT, blockReasonMessage=blocked");
	}

	@Test
	void describeFinishIsNullWithoutFinishOrBlockInfo() {
		GenerateContentResponse response = GenerateContentResponse.builder()
				.candidates(List.of(Candidate.builder().build()))
				.build();

		assertThat(GeminiLlmClient.describeFinish(response)).isNull();
	}

	private LlmStream open(String fallbackModel, Function<String, TestResponse> request) {
		return GeminiLlmClient.openStream(executor, PRIMARY, fallbackModel, request, TestResponse::chunks);
	}

	private static ServerException overloaded() {
		return new ServerException(503, "UNAVAILABLE", "The model is overloaded. Please try again later.");
	}

	/**
	 * 조각을 모두 내보낸 뒤 failure 가 있으면 그 예외를 던지는 응답.
	 */
	private record TestResponse(String model, List<String> chunkList, RuntimeException failure)
			implements AsyncLlmStream.ModelResponse {

		Iterator<String> chunks() {
			Iterator<String> delegate = chunkList.iterator();
			return new Iterator<>() {
				@Override
				public boolean hasNext() {
					if (delegate.hasNext()) {
						return true;
					}
					if (failure != null) {
						throw failure;
					}
					return false;
				}

				@Override
				public String next() {
					return delegate.next();
				}
			};
		}

		@Override
		public void close() {
		}
	}
}
