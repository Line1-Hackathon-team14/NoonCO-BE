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

	/**
	 * 기본으로 호출하는 모델 이름. 폴백하면 실제 응답 모델과 다를 수 있으므로 저장에는 {@link LlmStream#model()} 을 쓴다.
	 */
	String model();
}
