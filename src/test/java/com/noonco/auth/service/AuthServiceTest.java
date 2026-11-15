package com.noonco.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.noonco.auth.dto.AuthResponse;
import com.noonco.auth.dto.LoginRequest;
import com.noonco.auth.dto.SignupRequest;
import com.noonco.auth.dto.TokenResponse;
import com.noonco.global.ResultCode;
import com.noonco.global.exception.BusinessException;
import com.noonco.global.jwt.JwtTokenProvider;
import com.noonco.user.entity.Difficulty;
import com.noonco.user.entity.User;
import com.noonco.user.repository.UserRepository;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ AuthService.class, JwtTokenProvider.class, BCryptPasswordEncoder.class })
class AuthServiceTest {

	private static final String PASSWORD = "noonco1234";

	@Autowired AuthService authService;
	@Autowired UserRepository userRepository;
	@Autowired JwtTokenProvider jwtTokenProvider;

	// 로컬 DB 에 있을 수 있는 데이터와 겹치지 않는 이메일
	private final String email = "auth-test-" + UUID.randomUUID() + "@example.com";

	@Test
	void signupCreatesUserWithDefaultSettingsAndHashedPassword() {
		AuthResponse response = signup(email);

		assertThat(response.user().email()).isEqualTo(email);
		assertThat(response.user().difficulty()).isEqualTo(Difficulty.NORMAL);
		assertThat(response.user().speechRate()).isEqualByComparingTo(new BigDecimal("1.0"));
		assertThat(jwtTokenProvider.getUserId(response.accessToken())).isEqualTo(response.user().id());

		User saved = userRepository.findById(response.user().id()).orElseThrow();
		assertThat(saved.getPasswordHash()).startsWith("$2").isNotEqualTo(PASSWORD);
	}

	@Test
	void emailIsStoredLowercaseAndMatchedCaseInsensitively() {
		AuthResponse signup = signup("  " + email.toUpperCase() + " ");
		assertThat(signup.user().email()).isEqualTo(email);

		AuthResponse login = authService.login(new LoginRequest(email.toUpperCase(), PASSWORD));
		assertThat(login.user().id()).isEqualTo(signup.user().id());

		assertThatThrownBy(() -> signup(email.toUpperCase()))
				.isInstanceOf(BusinessException.class)
				.extracting("resultCode").isEqualTo(ResultCode.EMAIL_ALREADY_EXISTS);
	}

	@Test
	void loginFailsWithWrongPasswordOrUnknownEmail() {
		signup(email);

		assertThatThrownBy(() -> authService.login(new LoginRequest(email, "wrong1234")))
				.extracting("resultCode").isEqualTo(ResultCode.INVALID_CREDENTIALS);
		assertThatThrownBy(() -> authService.login(new LoginRequest("unknown-" + email, PASSWORD)))
				.extracting("resultCode").isEqualTo(ResultCode.INVALID_CREDENTIALS);
	}

	@Test
	void refreshRotatesTokenAndOldTokenCannotBeReused() {
		String oldToken = signup(email).refreshToken();

		TokenResponse rotated = authService.refresh(oldToken);

		assertThat(rotated.refreshToken()).isNotEqualTo(oldToken);
		assertThatThrownBy(() -> authService.refresh(oldToken))
				.extracting("resultCode").isEqualTo(ResultCode.INVALID_REFRESH_TOKEN);
		assertThat(authService.refresh(rotated.refreshToken()).accessToken()).isNotBlank();
	}

	@Test
	void loggedOutTokenCannotBeRefreshedAndLogoutIsIdempotent() {
		String token = signup(email).refreshToken();

		authService.logout(token);
		authService.logout(token);
		authService.logout("never-issued-token");

		assertThatThrownBy(() -> authService.refresh(token))
				.extracting("resultCode").isEqualTo(ResultCode.INVALID_REFRESH_TOKEN);
	}

	@Test
	void loginOnAnotherDeviceKeepsExistingRefreshToken() {
		String first = signup(email).refreshToken();
		authService.login(new LoginRequest(email, PASSWORD));

		assertThat(authService.refresh(first).accessToken()).isNotBlank();
	}

	private AuthResponse signup(String rawEmail) {
		return authService.signup(new SignupRequest("김눈치", rawEmail, PASSWORD));
	}
}
