package com.noonco.analysis.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.noonco.analysis.entity.AnalysisStatus;
import com.noonco.analysis.entity.VisualType;
import com.noonco.user.entity.Difficulty;

/**
 * 해설 스트림(NDJSON) 한 줄. type 마다 쓰는 필드만 채우고 나머지(null)는 내보내지 않는다.
 * 필드 순서가 곧 JSON 키 순서이므로 type 을 맨 앞에 둔다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisStreamEvent(String type, Long analysisId, Difficulty difficulty, String stage,
		VisualType visualType, String title, String section, String text, AnalysisStatus status) {

	public static final String STAGE_ANALYZING = "ANALYZING";
	public static final String STAGE_GENERATING = "GENERATING";

	public static AnalysisStreamEvent started(Long analysisId, Difficulty difficulty) {
		return new AnalysisStreamEvent("started", analysisId, difficulty, null, null, null, null, null, null);
	}

	public static AnalysisStreamEvent progress(String stage) {
		return new AnalysisStreamEvent("progress", null, null, stage, null, null, null, null, null);
	}

	/**
	 * PDF 는 visualType 이 null 이라 필드가 빠진다.
	 */
	public static AnalysisStreamEvent meta(VisualType visualType, String title) {
		return new AnalysisStreamEvent("meta", null, null, null, visualType, title, null, null, null);
	}

	/**
	 * section 은 SUMMARY(요약) 또는 DETAIL(축·수치 상세)이다. 요약 delta 가 모두 나간 뒤 상세 delta 가 나간다.
	 */
	public static AnalysisStreamEvent delta(String section, String text) {
		return new AnalysisStreamEvent("delta", null, null, null, null, null, section, text, null);
	}

	/**
	 * 연결 유지·끊김 감지용. FE 는 무시한다.
	 */
	public static AnalysisStreamEvent ping() {
		return new AnalysisStreamEvent("ping", null, null, null, null, null, null, null, null);
	}

	public static AnalysisStreamEvent done(Long analysisId) {
		return new AnalysisStreamEvent("done", analysisId, null, null, null, null, null, null, AnalysisStatus.COMPLETED);
	}
}
