package com.noonco.ai;

/**
 * 모델 출력이 약속한 형식(첫 줄 메타 JSON + 본문)을 따르지 않을 때 던진다.
 * 사용자 응답 변환은 호출하는 쪽에서 처리한다.
 */
public class LlmOutputFormatException extends RuntimeException {

	public LlmOutputFormatException(String message) {
		super(message);
	}

	public LlmOutputFormatException(String message, Throwable cause) {
		super(message, cause);
	}
}
