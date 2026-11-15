package com.noonco.material.dto;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import com.noonco.material.entity.Material;
import com.noonco.material.entity.SourceType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "자료 업로드 결과")
public record MaterialUploadResponse(
		@Schema(description = "자료 ID", example = "11")
		Long materialId,
		@Schema(description = "자료 유형", example = "PDF")
		SourceType sourceType,
		@Schema(description = "AI 생성 제목. 해설 완료 전이므로 업로드 직후에는 null", example = "null", nullable = true)
		String title,
		@Schema(description = "파일 크기(바이트)", example = "2516582")
		Integer fileSize,
		@Schema(description = "PDF 쪽수. IMAGE 는 null", example = "12", nullable = true)
		Integer pageCount,
		@Schema(description = "업로드 시각(KST)", example = "2026-11-09T14:32:00")
		LocalDateTime createdAt) {

	public static MaterialUploadResponse of(Material material) {
		return new MaterialUploadResponse(material.getId(), material.getSourceType(), material.getTitle(),
				material.getFileSize(), material.getPageCount(), roundToSeconds(material.getCreatedAt()));
	}

	/**
	 * 저장 직후 엔티티의 createdAt 에는 나노초가 남아 있지만, DB(DATETIME)는 소수 초를 반올림해 저장한다.
	 * 나중에 조회한 값과 같도록 응답도 같은 방식으로 초 단위로 반올림한다.
	 */
	private static LocalDateTime roundToSeconds(LocalDateTime time) {
		return time.plusNanos(500_000_000).truncatedTo(ChronoUnit.SECONDS);
	}
}
