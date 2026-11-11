package com.noonco.ai;

import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;

import lombok.extern.slf4j.Slf4j;

/**
 * 첫 응답까지 블로킹되는 요청을 별도 스레드에서 시작해 두고, 즉시 반환되는 스트림.
 * 응답(R)을 기다리는 동안 close 하면 대기 중인 요청 스레드를 인터럽트하고(재시도 대기·소켓 읽기 중단),
 * 순회 중인 스레드도 곧바로 끝난다. close 이후 늦게 도착한 응답은 즉시 닫는다.
 *
 * @param <R> 조각을 읽어 낼 응답. close 로 연결을 끊을 수 있어야 하고, 응답한 모델을 알려 준다.
 */
@Slf4j
final class AsyncLlmStream<R extends AsyncLlmStream.ModelResponse> extends AbstractLlmStream {

	/**
	 * 요청 결과. 폴백으로 요청 모델이 바뀔 수 있어 응답이 실제 모델 이름을 들고 있다.
	 */
	interface ModelResponse extends AutoCloseable {

		String model();
	}

	private final CompletableFuture<R> response = new CompletableFuture<>();
	private final Function<R, Iterator<String>> reader;
	private final Future<?> requestTask;
	private Iterator<String> chunks;

	/**
	 * @param executor 요청을 실행할 실행기. 인터럽트로 소켓 I/O 가 끊기도록 가상 스레드 실행기를 권장한다.
	 * @param request  첫 응답이 올 때까지 블로킹되는 요청
	 * @param reader   응답에서 텍스트 조각을 읽는 반복자를 만든다. 반복자는 null 을 내보내면 안 된다.
	 */
	AsyncLlmStream(ExecutorService executor, Callable<R> request, Function<R, Iterator<String>> reader) {
		this.reader = reader;
		this.requestTask = executor.submit(() -> {
			try {
				R result = request.call();
				// close 가 먼저 이겨 future 가 취소됐다면 아무도 이 응답을 읽지 않으므로 바로 닫는다.
				if (!response.complete(result)) {
					closeQuietly(result);
				}
			} catch (Throwable e) {
				response.completeExceptionally(e);
			}
		});
	}

	@Override
	protected String fetchNext() {
		if (chunks == null) {
			chunks = reader.apply(awaitResponse());
		}
		return chunks.hasNext() ? chunks.next() : null;
	}

	@Override
	public String model() {
		if (!response.isDone() || response.isCompletedExceptionally()) {
			return null;
		}
		return response.getNow(null).model();
	}

	@Override
	protected void onClose() {
		if (response.cancel(false)) {
			// 아직 응답 전: 요청 스레드를 인터럽트해 재시도 대기나 연결 대기를 끊는다.
			requestTask.cancel(true);
			return;
		}
		if (!response.isCompletedExceptionally()) {
			closeQuietly(response.getNow(null));
		}
	}

	private R awaitResponse() {
		try {
			return response.get();
		} catch (ExecutionException e) {
			throw asRuntime(e.getCause());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			close();
			throw new CancellationException("응답 대기 중 스레드가 인터럽트되었습니다.");
		}
	}

	private static RuntimeException asRuntime(Throwable cause) {
		if (cause instanceof RuntimeException runtimeException) {
			return runtimeException;
		}
		if (cause instanceof Error error) {
			throw error;
		}
		return new CompletionException(cause);
	}

	private static void closeQuietly(AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		} catch (Exception e) {
			log.debug("LLM 응답 정리 중 예외 무시: {}", e.getClass().getName());
		}
	}
}
