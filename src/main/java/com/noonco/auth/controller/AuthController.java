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

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;

	@PostMapping("/signup")
	public ResponseEntity<RsData<AuthResponse>> signup(@Valid @RequestBody SignupRequest request) {
		return ResponseEntity.status(ResultCode.CREATED.getStatus())
				.body(RsData.success(ResultCode.CREATED, authService.signup(request)));
	}

	@PostMapping("/login")
	public RsData<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
		return RsData.success(authService.login(request));
	}

	@PostMapping("/refresh")
	public RsData<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
		return RsData.success(authService.refresh(request.refreshToken()));
	}

	@PostMapping("/logout")
	public RsData<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
		authService.logout(request.refreshToken());
		return RsData.success(null);
	}
}
