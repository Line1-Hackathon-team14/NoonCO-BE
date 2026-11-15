package com.noonco.auth.dto;

import java.util.Locale;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "회원가입 요청")
public record SignupRequest(
		@Schema(description = "이름(홈·마이페이지 표시), 50자 이하", example = "김눈치")
		@NotBlank(message = "이름을 입력해 주세요.")
		@Size(max = 50, message = "이름은 50자 이하로 입력해 주세요.")
		String name,

		@Schema(description = "이메일(로그인 ID), 255자 이하. 소문자로 바꿔 저장한다", example = "noonchi@example.com")
		@NotBlank(message = "이메일을 입력해 주세요.")
		@Email(message = "이메일 형식을 확인해 주세요.")
		@Size(max = 255, message = "이메일은 255자 이하로 입력해 주세요.")
		String email,

		@Schema(description = "비밀번호. 8~64자, 숫자 1개 이상 포함", example = "password1")
		@NotBlank(message = "비밀번호를 입력해 주세요.")
		@Size(min = 8, max = 64, message = "8자 이상, 숫자를 포함해 주세요.")
		@Pattern(regexp = ".*\\d.*", message = "8자 이상, 숫자를 포함해 주세요.")
		String password
) {

	public SignupRequest {
		email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
	}
}
