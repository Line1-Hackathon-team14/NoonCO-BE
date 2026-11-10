package com.noonco.material.dto;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import com.noonco.material.entity.Material;
import com.noonco.material.entity.SourceType;

public record MaterialUploadResponse(Long materialId, SourceType sourceType, String title, Integer fileSize,
		Integer pageCount, LocalDateTime createdAt) {

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
