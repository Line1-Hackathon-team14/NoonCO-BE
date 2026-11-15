package com.noonco.auth.dto;

import com.noonco.user.dto.UserResponse;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인·회원가입 응답")
public record AuthResponse(
		@Schema(description = "Access Token. API 호출 시 `Authorization: Bearer {accessToken}` 헤더로 보낸다", example = "eyJhbGciOiJIUzUxMiJ9...")
		String accessToken,

		@Schema(description = "Refresh Token. Access Token 재발급과 로그아웃에 사용한다", example = "c21bX2k9Qm...")
		String refreshToken,

		@Schema(description = "로그인한 사용자 정보")
		UserResponse user
) {
}
