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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "내 정보", description = "로그인한 사용자 정보와 설정 (Authorization: Bearer {accessToken} 필요)")
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {

	private final UserService userService;

	@Operation(summary = "내 정보 조회", description = "이름, 이메일, 난이도, 말하기 속도를 조회한다.")
	@GetMapping
	public RsData<UserResponse> getMe(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
		return RsData.success(userService.getMe(userId));
	}

	@Operation(summary = "설정 변경", description = """
			난이도와 말하기 속도 중 보낸 값만 변경한다. 최소 1개는 보내야 한다.
			에러: VALIDATION_FAILED(400, 둘 다 없음, 말하기 속도가 0.5~2.0 밖이거나 소수 둘째 자리 이상)""")
	@PatchMapping("/settings")
	public RsData<UserSettingsResponse> updateSettings(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
			@Valid @RequestBody UpdateSettingsRequest request) {
		return RsData.success(userService.updateSettings(userId, request));
	}
}
