package com.noonco.ai;

/**
 * LLM 응답 텍스트 조각 스트림. 한 번만 순회할 수 있다.
 * close 를 호출하면 진행 중인 호출이 중단되고, 다른 스레드에서 순회 중이던 루프도 조용히 끝난다.
 */
public interface LlmStream extends Iterable<String>, AutoCloseable {

	boolean isClosed();

	@Override
	void close();
}
