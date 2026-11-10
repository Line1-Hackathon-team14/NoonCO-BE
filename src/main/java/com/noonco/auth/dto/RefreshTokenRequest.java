package com.noonco.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(
		@NotBlank(message = "refreshToken을 입력해 주세요.")
		String refreshToken
) {
}
