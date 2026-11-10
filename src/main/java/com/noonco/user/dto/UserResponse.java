package com.noonco.user.dto;

import java.math.BigDecimal;

import com.noonco.user.entity.Difficulty;
import com.noonco.user.entity.User;
import com.noonco.user.entity.UserSettings;

public record UserResponse(Long id, String name, String email, Difficulty difficulty, BigDecimal speechRate) {

	public static UserResponse of(User user, UserSettings settings) {
		return new UserResponse(user.getId(), user.getName(), user.getEmail(), settings.getDifficulty(),
				settings.getSpeechRate());
	}
}
