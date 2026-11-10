package com.noonco.auth.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.noonco.auth.dto.AuthResponse;
import com.noonco.auth.dto.LoginRequest;
import com.noonco.auth.dto.RefreshTokenRequest;
import com.noonco.auth.dto.SignupRequest;
import com.noonco.auth.dto.TokenResponse;
import com.noonco.auth.service.AuthService;
import com.noonco.global.ResultCode;
import com.noonco.global.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "인증", description = "회원가입, 로그인, 토큰 재발급, 로그아웃")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;

	@Operation(summary = "회원가입", description = "가입 후 바로 로그인 상태가 되도록 토큰을 함께 응답한다. 설정은 기본값(NORMAL, 1.0)으로 생성된다.")
	@PostMapping("/signup")
	public ResponseEntity<RsData<AuthResponse>> signup(@Valid @RequestBody SignupRequest request) {
		return ResponseEntity.status(ResultCode.CREATED.getStatus())
				.body(RsData.success(ResultCode.CREATED, authService.signup(request)));
	}

	@Operation(summary = "로그인", description = "이메일과 비밀번호로 로그인하고 Access Token, Refresh Token을 발급한다.")
	@PostMapping("/login")
	public RsData<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
		return RsData.success(authService.login(request));
	}

	@Operation(summary = "토큰 재발급", description = "Refresh Token으로 새 Access Token과 Refresh Token을 발급한다. 기존 Refresh Token은 폐기된다.")
	@PostMapping("/refresh")
	public RsData<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
		return RsData.success(authService.refresh(request.refreshToken()));
	}

	@Operation(summary = "로그아웃", description = "Refresh Token을 폐기한다.")
	@PostMapping("/logout")
	public RsData<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
		authService.logout(request.refreshToken());
		return RsData.success(null);
	}
}
