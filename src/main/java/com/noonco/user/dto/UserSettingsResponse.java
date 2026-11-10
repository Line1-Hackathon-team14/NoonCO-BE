package com.noonco.user.dto;

import java.math.BigDecimal;

import com.noonco.user.entity.Difficulty;
import com.noonco.user.entity.UserSettings;

public record UserSettingsResponse(Difficulty difficulty, BigDecimal speechRate) {

	public static UserSettingsResponse of(UserSettings settings) {
		return new UserSettingsResponse(settings.getDifficulty(), settings.getSpeechRate());
	}
}
