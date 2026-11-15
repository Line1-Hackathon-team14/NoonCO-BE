package com.noonco.material.dto;

import com.noonco.material.entity.SourceType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Schema(description = "학습 기록 목록 조회 조건 (Query)")
public record MaterialListRequest(
		@Schema(description = "자료 유형 필터. 없으면 전체", example = "PDF", nullable = true)
		SourceType sourceType,
		@Schema(description = "페이지 번호. 0부터 시작, 기본 0", example = "0", nullable = true)
		@Min(value = 0, message = "0 이상이어야 합니다.")
		Integer page,
		@Schema(description = "페이지 크기. 1~50, 기본 20", example = "20", nullable = true)
		@Min(value = 1, message = "1~50 사이여야 합니다.")
		@Max(value = 50, message = "1~50 사이여야 합니다.")
		Integer size) {

	private static final int DEFAULT_SIZE = 20;

	public int pageOrDefault() {
		return page == null ? 0 : page;
	}

	public int sizeOrDefault() {
		return size == null ? DEFAULT_SIZE : size;
	}
}
