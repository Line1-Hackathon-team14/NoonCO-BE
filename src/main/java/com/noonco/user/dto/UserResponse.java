package com.noonco.user.dto;

import java.math.BigDecimal;

import com.noonco.user.entity.Difficulty;
import com.noonco.user.entity.User;
import com.noonco.user.entity.UserSettings;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "사용자 정보")
public record UserResponse(
		@Schema(description = "사용자 ID", example = "1")
		Long id,

		@Schema(description = "이름", example = "김눈치")
		String name,

		@Schema(description = "이메일(로그인 ID)", example = "noonchi@example.com")
		String email,

		@Schema(description = "해설 난이도", example = "NORMAL")
		Difficulty difficulty,

		@Schema(description = "말하기 속도", example = "1.0")
		BigDecimal speechRate
) {

	public static UserResponse of(User user, UserSettings settings) {
		return new UserResponse(user.getId(), user.getName(), user.getEmail(), settings.getDifficulty(),
				settings.getSpeechRate());
	}
}
