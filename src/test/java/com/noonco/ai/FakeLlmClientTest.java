package com.noonco.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class FakeLlmClientTest {

	private static LlmClient fakeClient(Duration chunkDelay) {
		return new FakeLlmClient(new LlmProperties(
				"fake",
				new LlmProperties.Gemini(null, "unused", 1.0f, 1, Duration.ofSeconds(1), 1),
				new LlmProperties.Fake(chunkDelay)));
	}

	@Test
	void streamsMetaLineInSeveralChunksThenExplanation() {
		List<String> chunks = new ArrayList<>();
		try (LlmStream stream = fakeClient(Duration.ZERO).stream(LlmRequest.ofFile("프롬프트", new byte[] {1}, "image/png"))) {
			stream.forEach(chunks::add);
		}

		String full = String.join("", chunks);
		String firstLine = full.substring(0, full.indexOf('\n'));
		assertThat(firstLine).startsWith("{\"visualType\":\"LINE\",\"title\":\"").endsWith("\"}");
		assertThat(full.substring(full.indexOf('\n') + 1)).isNotBlank();

		// 첫 줄이 한 조각에 다 들어있지 않아야 메타 파싱(#11)이 조각 경계를 처리하는지 검증할 수 있다.
		assertThat(chunks).noneMatch(chunk -> chunk.contains(firstLine));
		long metaChunkCount = chunks.stream().takeWhile(chunk -> !chunk.contains("\n")).count();
		assertThat(metaChunkCount).isGreaterThanOrEqualTo(2);
	}

	@Test
	void pdfRequestStreamsTitleOnlyMetaLine() {
		List<String> chunks = new ArrayList<>();
		try (LlmStream stream = fakeClient(Duration.ZERO).stream(LlmRequest.ofFile("프롬프트", new byte[] {1}, "application/pdf"))) {
			stream.forEach(chunks::add);
		}

		String full = String.join("", chunks);
		String firstLine = full.substring(0, full.indexOf('\n'));
		assertThat(firstLine).startsWith("{\"title\":\"").endsWith("\"}").doesNotContain("visualType");
		assertThat(full.substring(full.indexOf('\n') + 1)).isNotBlank();
		assertThat(chunks).noneMatch(chunk -> chunk.contains(firstLine));
	}

	@Test
	void closeFromAnotherThreadStopsIteration() throws Exception {
		LlmStream stream = fakeClient(Duration.ofSeconds(1)).stream(LlmRequest.ofFile("프롬프트", new byte[] {1}, "image/png"));
		List<String> received = new ArrayList<>();
		CompletableFuture<Void> reading = CompletableFuture.runAsync(() -> stream.forEach(received::add));

		Thread.sleep(100);
		long closedAt = System.nanoTime();
		stream.close();
		reading.get(2, TimeUnit.SECONDS);

		assertThat(Duration.ofNanos(System.nanoTime() - closedAt)).isLessThan(Duration.ofMillis(500));
		assertThat(received).isEmpty();
		assertThat(stream.isClosed()).isTrue();
	}

	@Test
	void streamCanBeIteratedOnlyOnce() {
		try (LlmStream stream = fakeClient(Duration.ZERO).stream(LlmRequest.ofFile("프롬프트", new byte[] {1}, "image/png"))) {
			stream.iterator();
			assertThatThrownBy(stream::iterator).isInstanceOf(IllegalStateException.class);
		}
	}
}
