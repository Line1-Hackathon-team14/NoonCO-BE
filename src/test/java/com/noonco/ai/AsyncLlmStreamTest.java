package com.noonco.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Gemini 의 "첫 응답까지 블로킹되는 요청"을 지연 요청으로 흉내 내서, 실제 호출 없이 취소 동작을 검증한다.
 */
class AsyncLlmStreamTest {

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	@AfterEach
	void tearDown() {
		executor.shutdownNow();
	}

	@Test
	void streamIsReturnedImmediatelyWhileRequestIsPending() {
		long startedAt = System.nanoTime();
		try (LlmStream stream = new AsyncLlmStream<>(executor, slowRequest(new CountDownLatch(1)), FakeResponse::chunks)) {
			assertThat(elapsedSince(startedAt)).isLessThan(Duration.ofMillis(500));
		}
	}

	@Test
	void closeWhileIteratorWaitsForFirstResponseEndsIterationImmediately() throws Exception {
		CountDownLatch requestInterrupted = new CountDownLatch(1);
		LlmStream stream = new AsyncLlmStream<>(executor, slowRequest(requestInterrupted), FakeResponse::chunks);
		List<String> received = new ArrayList<>();
		CompletableFuture<Void> reading = CompletableFuture.runAsync(() -> stream.forEach(received::add), executor);

		Thread.sleep(200);
		assertThat(reading).isNotDone();

		long closedAt = System.nanoTime();
		stream.close();
		reading.get(2, TimeUnit.SECONDS);

		assertThat(elapsedSince(closedAt)).isLessThan(Duration.ofMillis(500));
		assertThat(received).isEmpty();
		// 대기 중이던 요청 스레드도 인터럽트되어 재시도 대기 등이 끊긴다.
		assertThat(requestInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void closeBeforeIterationEndsIterationImmediately() {
		LlmStream stream = new AsyncLlmStream<>(executor, slowRequest(new CountDownLatch(1)), FakeResponse::chunks);
		stream.close();

		long startedAt = System.nanoTime();
		assertThat(stream).isEmpty();
		assertThat(elapsedSince(startedAt)).isLessThan(Duration.ofMillis(500));
	}

	@Test
	void closeBeforeRequestStartsSkipsRequest() throws Exception {
		ExecutorService notStarted = Executors.newSingleThreadExecutor();
		CountDownLatch blocker = new CountDownLatch(1);
		notStarted.submit(() -> {
			blocker.await();
			return null;
		});
		AtomicBoolean requested = new AtomicBoolean();
		try {
			LlmStream stream = new AsyncLlmStream<>(notStarted, () -> {
				requested.set(true);
				return new FakeResponse(List.of("보내면 안 되는 요청"));
			}, FakeResponse::chunks);
			stream.close();
			blocker.countDown();
			notStarted.shutdown();
			assertThat(notStarted.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
			assertThat(requested).isFalse();
		} finally {
			notStarted.shutdownNow();
		}
	}

	@Test
	void responseArrivingAfterCloseIsClosed() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		FakeResponse lateResponse = new FakeResponse(List.of("늦은 응답"));
		Callable<FakeResponse> uninterruptibleRequest = () -> {
			started.countDown();
			// 인터럽트를 무시하고 끝까지 응답을 만들어 내는 요청
			while (true) {
				try {
					release.await();
					return lateResponse;
				} catch (InterruptedException ignored) {
				}
			}
		};
		LlmStream stream = new AsyncLlmStream<>(executor, uninterruptibleRequest, FakeResponse::chunks);
		assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

		stream.close();
		release.countDown();

		assertThat(lateResponse.closed.await(1, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void closeDuringChunkReadingClosesResponse() {
		FakeResponse response = new FakeResponse(List.of("첫 조각", "둘째 조각", "셋째 조각"));
		List<String> received = new ArrayList<>();
		try (LlmStream stream = new AsyncLlmStream<>(executor, () -> response, FakeResponse::chunks)) {
			for (String chunk : stream) {
				received.add(chunk);
				stream.close();
			}
		}

		assertThat(received).containsExactly("첫 조각");
		assertThat(response.closed.getCount()).isZero();
	}

	@Test
	void streamsAllChunksWhenNotClosed() {
		FakeResponse response = new FakeResponse(List.of("a", "b", "c"));
		try (LlmStream stream = new AsyncLlmStream<>(executor, () -> response, FakeResponse::chunks)) {
			assertThat(stream).containsExactly("a", "b", "c");
			assertThat(stream.model()).isEqualTo("fake-response-model");
		}
		assertThat(response.closed.getCount()).isZero();
	}

	@Test
	void requestFailureIsRethrownToIteratingThread() {
		IllegalStateException failure = new IllegalStateException("503 UNAVAILABLE");
		try (LlmStream stream = new AsyncLlmStream<FakeResponse>(executor, () -> {
			throw failure;
		}, FakeResponse::chunks)) {
			assertThatThrownBy(() -> stream.iterator().hasNext()).isSameAs(failure);
		}
	}

	/** 인터럽트될 때까지 응답하지 않는 요청. SDK 의 재시도 대기(Thread.sleep)를 흉내 낸다. */
	private static Callable<FakeResponse> slowRequest(CountDownLatch interrupted) {
		return () -> {
			try {
				Thread.sleep(Duration.ofMinutes(5));
			} catch (InterruptedException e) {
				interrupted.countDown();
				throw e;
			}
			return new FakeResponse(List.of("너무 늦은 응답"));
		};
	}

	private static Duration elapsedSince(long startedAt) {
		return Duration.ofNanos(System.nanoTime() - startedAt);
	}

	private static final class FakeResponse implements AsyncLlmStream.ModelResponse {

		private final List<String> chunks;
		private final CountDownLatch closed = new CountDownLatch(1);
		private final AtomicBoolean open = new AtomicBoolean(true);

		FakeResponse(List<String> chunks) {
			this.chunks = chunks;
		}

		Iterator<String> chunks() {
			Iterator<String> delegate = chunks.iterator();
			return new Iterator<>() {
				@Override
				public boolean hasNext() {
					if (!open.get()) {
						throw new IllegalStateException("이미 닫힌 응답");
					}
					return delegate.hasNext();
				}

				@Override
				public String next() {
					return delegate.next();
				}
			};
		}

		@Override
		public String model() {
			return "fake-response-model";
		}

		@Override
		public void close() {
			open.set(false);
			closed.countDown();
		}
	}
}
