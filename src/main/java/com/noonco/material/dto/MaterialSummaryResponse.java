package com.noonco.material.dto;

import java.time.LocalDateTime;

import com.noonco.analysis.entity.VisualType;
import com.noonco.material.entity.SourceType;
import com.noonco.user.entity.Difficulty;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "학습 기록 목록의 자료 1건")
public record MaterialSummaryResponse(
		@Schema(description = "자료 ID", example = "11")
		Long materialId,
		@Schema(description = "자료 유형", example = "PDF")
		SourceType sourceType,
		@Schema(description = "AI 생성 제목", example = "경제학_3주차_수요와공급", nullable = true)
		String title,
		@Schema(description = "시각 자료 유형: LINE, BAR, PIE, TABLE, OTHER. PDF 는 null", example = "null", nullable = true)
		VisualType visualType,
		@Schema(description = "PDF 쪽수. IMAGE 는 null", example = "12", nullable = true)
		Integer pageCount,
		@Schema(description = "해설 당시 난이도", example = "NORMAL")
		Difficulty difficulty,
		@Schema(description = "업로드 시각(KST)", example = "2026-11-09T14:32:00")
		LocalDateTime createdAt) {
}
