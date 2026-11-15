package com.noonco.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "토큰 재발급 응답")
public record TokenResponse(
		@Schema(description = "새 Access Token", example = "eyJhbGciOiJIUzUxMiJ9...")
		String accessToken,

		@Schema(description = "새 Refresh Token. 기존 Refresh Token은 더 이상 쓸 수 없다", example = "c21bX2k9Qm...")
		String refreshToken
) {
}
