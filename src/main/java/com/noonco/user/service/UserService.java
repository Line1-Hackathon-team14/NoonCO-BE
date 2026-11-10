package com.noonco.user.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.noonco.global.ResultCode;
import com.noonco.global.exception.BusinessException;
import com.noonco.user.dto.UpdateSettingsRequest;
import com.noonco.user.dto.UserResponse;
import com.noonco.user.dto.UserSettingsResponse;
import com.noonco.user.entity.User;
import com.noonco.user.entity.UserSettings;
import com.noonco.user.repository.UserRepository;
import com.noonco.user.repository.UserSettingsRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class UserService {

	private final UserRepository userRepository;
	private final UserSettingsRepository userSettingsRepository;

	@Transactional(readOnly = true)
	public UserResponse getMe(Long userId) {
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND));
		return UserResponse.of(user, findSettings(userId));
	}

	public UserSettingsResponse updateSettings(Long userId, UpdateSettingsRequest request) {
		UserSettings settings = findSettings(userId);
		if (request.difficulty() != null) {
			settings.changeDifficulty(request.difficulty());
		}
		if (request.speechRate() != null) {
			settings.changeSpeechRate(request.speechRate());
		}
		return UserSettingsResponse.of(settings);
	}

	private UserSettings findSettings(Long userId) {
		return userSettingsRepository.findById(userId)
				.orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND));
	}
}
