package com.noonco.ai;

import com.noonco.analysis.entity.VisualType;

/**
 * 모델 출력 파서({@link LlmOutputParser})가 내보내는 이벤트.
 */
public sealed interface LlmOutputEvent {

	/**
	 * 첫 줄 메타 JSON. PDF 출력이면 visualType 은 null 이다.
	 */
	record Meta(VisualType visualType, String title) implements LlmOutputEvent {
	}

	/**
	 * 이미지 판독 불가 응답. 이후 텍스트는 무시된다.
	 */
	record Unreadable() implements LlmOutputEvent {
	}

	/**
	 * 해설 본문 조각. 메타 줄과 그 뒤 줄바꿈은 포함하지 않는다.
	 */
	record Delta(String text) implements LlmOutputEvent {
	}
}
