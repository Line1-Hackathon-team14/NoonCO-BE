package com.noonco.ai;

/**
 * LLM 스트리밍 호출 추상화.
 * 구현체는 llm.provider 설정값(gemini | fake)에 따라 하나만 등록된다.
 */
public interface LlmClient {

	/**
	 * 호출을 시작하고 응답 텍스트 조각을 순서대로 내보내는 스트림을 반환한다.
	 * 반환된 스트림은 반드시 close 해야 하며, 수신 도중 close 하면 호출이 중단된다.
	 */
	LlmStream stream(LlmRequest request);
}
