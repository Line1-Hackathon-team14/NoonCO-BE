package com.noonco.material.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "학습 기록 목록 페이지")
public record MaterialPageResponse(
		@Schema(description = "자료 목록 (최신순)")
		List<MaterialSummaryResponse> content,
		@Schema(description = "현재 페이지 번호", example = "0")
		int page,
		@Schema(description = "페이지 크기", example = "20")
		int size,
		@Schema(description = "조건에 맞는 전체 자료 수", example = "1")
		long totalElements,
		@Schema(description = "다음 페이지 존재 여부", example = "false")
		boolean hasNext) {

	public static MaterialPageResponse of(Page<MaterialSummaryResponse> page) {
		return new MaterialPageResponse(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
				page.hasNext());
	}
}
