package com.noonco.material.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "학습 기록 개수. GET /materials 목록과 같은 조건(완료된 해설이 있는 자료)으로 센다")
public record MaterialCountResponse(
		@Schema(description = "전체", example = "12")
		long total,
		@Schema(description = "사진", example = "8")
		long image,
		@Schema(description = "PDF", example = "4")
		long pdf) {
}
