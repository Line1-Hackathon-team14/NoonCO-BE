package com.noonco.ai;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 한 번만 순회 가능하고, close 이후에는 순회가 조용히 끝나는 스트림의 공통 구현.
 * close 로 인해 하위 호출에서 발생한 예외는 삼키고, 그 외의 예외는 그대로 던진다.
 */
abstract class AbstractLlmStream implements LlmStream {

	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicBoolean iterated = new AtomicBoolean();

	/**
	 * 다음 텍스트 조각을 가져온다. 더 이상 없으면 null.
	 */
	protected abstract String fetchNext();

	/**
	 * 진행 중인 호출을 중단하고 자원을 정리한다. close 시 한 번만 호출된다.
	 */
	protected abstract void onClose();

	@Override
	public Iterator<String> iterator() {
		if (!iterated.compareAndSet(false, true)) {
			throw new IllegalStateException("LlmStream 은 한 번만 순회할 수 있습니다.");
		}
		return new ChunkIterator();
	}

	@Override
	public boolean isClosed() {
		return closed.get();
	}

	@Override
	public void close() {
		if (closed.compareAndSet(false, true)) {
			onClose();
		}
	}

	private class ChunkIterator implements Iterator<String> {

		private String next;
		private boolean done;

		@Override
		public boolean hasNext() {
			if (done || closed.get()) {
				return false;
			}
			if (next != null) {
				return true;
			}
			try {
				next = fetchNext();
			} catch (RuntimeException e) {
				if (closed.get()) {
					done = true;
					return false;
				}
				throw e;
			}
			if (next == null || closed.get()) {
				next = null;
				done = true;
				return false;
			}
			return true;
		}

		@Override
		public String next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			String chunk = next;
			next = null;
			return chunk;
		}
	}
}
