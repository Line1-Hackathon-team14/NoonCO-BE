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
	 * 해설 본문 조각. 메타 줄과 그 뒤 줄바꿈, 구분선 줄과 그 앞뒤 줄바꿈은 포함하지 않는다.
	 * 요약 조각이 모두 나온 뒤에 상세 조각이 나온다.
	 */
	record Delta(Section section, String text) implements LlmOutputEvent {
	}

	/**
	 * 본문 구간. 구분선 앞은 요약, 뒤는 축·수치 상세 해설이다.
	 */
	enum Section {
		SUMMARY, DETAIL
	}
}
