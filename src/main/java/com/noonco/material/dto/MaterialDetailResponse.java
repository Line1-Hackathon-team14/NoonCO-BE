package com.noonco.material.dto;

import java.time.LocalDateTime;

import com.noonco.analysis.entity.Analysis;
import com.noonco.analysis.entity.VisualType;
import com.noonco.material.entity.Material;
import com.noonco.material.entity.SourceType;
import com.noonco.user.entity.Difficulty;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "학습 기록 상세")
public record MaterialDetailResponse(
		@Schema(description = "자료 ID", example = "10")
		Long materialId,
		@Schema(description = "자료 유형", example = "IMAGE")
		SourceType sourceType,
		@Schema(description = "AI 생성 제목. 해설 완료 전이면 null", example = "11월 9일 촬영 자료", nullable = true)
		String title,
		@Schema(description = "원본 파일명", example = "null", nullable = true)
		String originalFilename,
		@Schema(description = "파일 크기(바이트)", example = "1843200")
		Integer fileSize,
		@Schema(description = "PDF 쪽수. IMAGE 는 null", example = "null", nullable = true)
		Integer pageCount,
		@Schema(description = "업로드 시각(KST)", example = "2026-11-09T14:30:00")
		LocalDateTime createdAt,
		@Schema(description = "최신 완료 해설. 없으면 null", nullable = true)
		AnalysisResponse analysis) {

	public static MaterialDetailResponse of(Material material, Analysis completed) {
		return new MaterialDetailResponse(material.getId(), material.getSourceType(), material.getTitle(),
				material.getOriginalFilename(), material.getFileSize(), material.getPageCount(),
				material.getCreatedAt(), completed == null ? null : AnalysisResponse.of(completed));
	}

	@Schema(description = "완료된 해설")
	public record AnalysisResponse(
			@Schema(description = "분석 ID", example = "25")
			Long analysisId,
			@Schema(description = "시각 자료 유형: LINE, BAR, PIE, TABLE, OTHER. PDF 는 null", example = "LINE", nullable = true)
			VisualType visualType,
			@Schema(description = "해설 당시 난이도", example = "NORMAL")
			Difficulty difficulty,
			@Schema(description = "요약 해설(한 번 탭)", example = "이 그래프는 2020년부터 2025년까지 월평균 기온을 보여 줘요...")
			String summary,
			@Schema(description = "상세 해설(두 번 탭). 이미지는 축·단위·수치 상세, PDF 는 문서 속 표·그래프 설명. 없으면 null", nullable = true)
			String detail,
			@Schema(description = "해설 요청 시각(KST)", example = "2026-11-09T14:30:05")
			LocalDateTime createdAt) {

		static AnalysisResponse of(Analysis analysis) {
			return new AnalysisResponse(analysis.getId(), analysis.getVisualType(), analysis.getDifficulty(),
					analysis.getSummary(), analysis.getDetail(), analysis.getCreatedAt());
		}
	}
}
