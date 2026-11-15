package com.noonco.user.dto;

import java.math.BigDecimal;

import com.noonco.user.entity.Difficulty;
import com.noonco.user.entity.UserSettings;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "변경된 설정")
public record UserSettingsResponse(
		@Schema(description = "해설 난이도", example = "HARD")
		Difficulty difficulty,

		@Schema(description = "말하기 속도", example = "1.2")
		BigDecimal speechRate
) {

	public static UserSettingsResponse of(UserSettings settings) {
		return new UserSettingsResponse(settings.getDifficulty(), settings.getSpeechRate());
	}
}
