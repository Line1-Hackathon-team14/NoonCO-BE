package com.noonco.material.dto;

import java.time.LocalDateTime;

import com.noonco.material.entity.Material;
import com.noonco.material.entity.SourceType;

public record MaterialUploadResponse(Long materialId, SourceType sourceType, String title, Integer fileSize,
		Integer pageCount, LocalDateTime createdAt) {

	public static MaterialUploadResponse of(Material material) {
		return new MaterialUploadResponse(material.getId(), material.getSourceType(), material.getTitle(),
				material.getFileSize(), material.getPageCount(), material.getCreatedAt());
	}
}
