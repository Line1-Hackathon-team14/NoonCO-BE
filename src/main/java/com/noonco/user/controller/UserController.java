package com.noonco.user.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.noonco.global.RsData;
import com.noonco.user.dto.UpdateSettingsRequest;
import com.noonco.user.dto.UserResponse;
import com.noonco.user.dto.UserSettingsResponse;
import com.noonco.user.service.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {

	private final UserService userService;

	@GetMapping
	public RsData<UserResponse> getMe(@AuthenticationPrincipal Long userId) {
		return RsData.success(userService.getMe(userId));
	}

	@PatchMapping("/settings")
	public RsData<UserSettingsResponse> updateSettings(@AuthenticationPrincipal Long userId,
			@Valid @RequestBody UpdateSettingsRequest request) {
		return RsData.success(userService.updateSettings(userId, request));
	}
}
