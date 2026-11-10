package com.noonco.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "토큰 재발급·로그아웃 요청")
public record RefreshTokenRequest(
		@Schema(description = "로그인 시 받은 Refresh Token", example = "c21bX2k9Qm...")
		@NotBlank(message = "refreshToken을 입력해 주세요.")
		String refreshToken
) {
}
