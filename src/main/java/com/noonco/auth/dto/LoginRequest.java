package com.noonco.auth.dto;

import java.util.Locale;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "로그인 요청")
public record LoginRequest(
		@Schema(description = "이메일(로그인 ID). 앞뒤 공백을 지우고 소문자로 바꿔 비교한다", example = "noonchi@example.com")
		@NotBlank(message = "이메일을 입력해 주세요.")
		String email,

		@Schema(description = "비밀번호", example = "password1")
		@NotBlank(message = "비밀번호를 입력해 주세요.")
		String password
) {

	public LoginRequest {
		email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
	}
}
