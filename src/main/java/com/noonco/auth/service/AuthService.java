package com.noonco.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.noonco.auth.dto.AuthResponse;
import com.noonco.auth.dto.LoginRequest;
import com.noonco.auth.dto.SignupRequest;
import com.noonco.auth.dto.TokenResponse;
import com.noonco.auth.entity.RefreshToken;
import com.noonco.auth.repository.RefreshTokenRepository;
import com.noonco.global.ResultCode;
import com.noonco.global.exception.BusinessException;
import com.noonco.global.jwt.JwtProperties;
import com.noonco.global.jwt.JwtTokenProvider;
import com.noonco.user.dto.UserResponse;
import com.noonco.user.entity.User;
import com.noonco.user.entity.UserSettings;
import com.noonco.user.repository.UserRepository;
import com.noonco.user.repository.UserSettingsRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class AuthService {

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private final UserRepository userRepository;
	private final UserSettingsRepository userSettingsRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenProvider jwtTokenProvider;
	private final JwtProperties jwtProperties;

	public AuthResponse signup(SignupRequest request) {
		if (userRepository.existsByEmail(request.email())) {
			throw new BusinessException(ResultCode.EMAIL_ALREADY_EXISTS);
		}
		User user = User.builder()
				.name(request.name())
				.email(request.email())
				.passwordHash(passwordEncoder.encode(request.password()))
				.build();
		try {
			userRepository.saveAndFlush(user);
		} catch (DataIntegrityViolationException e) {
			// 동시 가입으로 unique 제약을 위반한 경우
			throw new BusinessException(ResultCode.EMAIL_ALREADY_EXISTS);
		}
		UserSettings settings = userSettingsRepository.save(new UserSettings(user));
		return toAuthResponse(user, settings);
	}

	public AuthResponse login(LoginRequest request) {
		User user = userRepository.findByEmail(request.email())
				.filter(found -> passwordEncoder.matches(request.password(), found.getPasswordHash()))
				.orElseThrow(() -> new BusinessException(ResultCode.INVALID_CREDENTIALS));
		UserSettings settings = userSettingsRepository.findById(user.getId()).orElseThrow();
		return toAuthResponse(user, settings);
	}

	// 만료 토큰도 삭제한 뒤 실패를 응답하므로 BusinessException 에서는 롤백하지 않는다.
	@Transactional(noRollbackFor = BusinessException.class)
	public TokenResponse refresh(String refreshToken) {
		String tokenHash = hash(refreshToken);
		RefreshToken token = refreshTokenRepository.findByTokenHash(tokenHash)
				.orElseThrow(() -> new BusinessException(ResultCode.INVALID_REFRESH_TOKEN));
		Long userId = token.getUser().getId();
		LocalDateTime expiresAt = token.getExpiresAt();

		if (refreshTokenRepository.deleteByTokenHash(tokenHash) == 0 || expiresAt.isBefore(LocalDateTime.now())) {
			throw new BusinessException(ResultCode.INVALID_REFRESH_TOKEN);
		}
		return issueTokens(userRepository.getReferenceById(userId));
	}

	public void logout(String refreshToken) {
		refreshTokenRepository.deleteByTokenHash(hash(refreshToken));
	}

	private AuthResponse toAuthResponse(User user, UserSettings settings) {
		TokenResponse tokens = issueTokens(user);
		return new AuthResponse(tokens.accessToken(), tokens.refreshToken(), UserResponse.of(user, settings));
	}

	private TokenResponse issueTokens(User user) {
		byte[] bytes = new byte[32];
		SECURE_RANDOM.nextBytes(bytes);
		String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

		refreshTokenRepository.save(RefreshToken.builder()
				.user(user)
				.tokenHash(hash(refreshToken))
				.expiresAt(LocalDateTime.now().plus(jwtProperties.refreshTokenExpiration()))
				.build());
		return new TokenResponse(jwtTokenProvider.createAccessToken(user.getId()), refreshToken);
	}

	private static String hash(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
