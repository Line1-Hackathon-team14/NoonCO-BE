package com.noonco.auth.dto;

import java.util.Locale;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
		@NotBlank(message = "이름을 입력해 주세요.")
		@Size(max = 50, message = "이름은 50자 이하로 입력해 주세요.")
		String name,

		@NotBlank(message = "이메일을 입력해 주세요.")
		@Email(message = "이메일 형식을 확인해 주세요.")
		@Size(max = 255, message = "이메일은 255자 이하로 입력해 주세요.")
		String email,

		@NotBlank(message = "비밀번호를 입력해 주세요.")
		@Size(min = 8, max = 64, message = "8자 이상, 숫자를 포함해 주세요.")
		@Pattern(regexp = ".*\\d.*", message = "8자 이상, 숫자를 포함해 주세요.")
		String password
) {

	public SignupRequest {
		email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
	}
}
