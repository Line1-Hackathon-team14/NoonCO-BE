package com.noonco.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.noonco.auth.dto.AuthResponse;
import com.noonco.auth.dto.SignupRequest;
import com.noonco.auth.dto.TokenResponse;
import com.noonco.auth.service.AuthService;
import com.noonco.global.ResultCode;
import com.noonco.global.config.SecurityConfig;
import com.noonco.global.exception.BusinessException;
import com.noonco.global.exception.GlobalExceptionHandler;
import com.noonco.global.jwt.JwtTokenProvider;
import com.noonco.user.dto.UserResponse;
import com.noonco.user.entity.Difficulty;

@WebMvcTest
@ContextConfiguration(classes = { AuthController.class, GlobalExceptionHandler.class, SecurityConfig.class,
		JwtTokenProvider.class })
@TestPropertySource(properties = "jwt.secret=test-secret-key-for-noonco-jwt-must-be-32-bytes-or-more")
class AuthControllerTest {

	private static final AuthResponse AUTH_RESPONSE = new AuthResponse("access", "refresh",
			new UserResponse(1L, "김눈치", "noonchi@example.com", Difficulty.NORMAL, new BigDecimal("1.0")));

	@Autowired MockMvc mockMvc;

	@MockitoBean AuthService authService;

	@Test
	void signupReturnsCreatedWithTokensAndUser() throws Exception {
		given(authService.signup(any())).willReturn(AUTH_RESPONSE);

		mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"김눈치","email":" Noonchi@Example.com ","password":"noonco1234"}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.resultCode").value("CREATED"))
				.andExpect(jsonPath("$.data.accessToken").value("access"))
				.andExpect(jsonPath("$.data.refreshToken").value("refresh"))
				.andExpect(jsonPath("$.data.user.id").value(1))
				.andExpect(jsonPath("$.data.user.difficulty").value("NORMAL"))
				.andExpect(jsonPath("$.data.user.speechRate").value(1.0));

		verify(authService).signup(new SignupRequest("김눈치", "noonchi@example.com", "noonco1234"));
	}

	@Test
	void signupRejectsPasswordWithoutDigit() throws Exception {
		mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"김눈치","email":"noonchi@example.com","password":"noonconoonco"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.resultCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.data[0].field").value("password"))
				.andExpect(jsonPath("$.data[0].message").value("8자 이상, 숫자를 포함해 주세요."));
	}

	@Test
	void loginReturnsOk() throws Exception {
		given(authService.login(any())).willReturn(AUTH_RESPONSE);

		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"noonchi@example.com","password":"noonco1234"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resultCode").value("OK"))
				.andExpect(jsonPath("$.data.user.email").value("noonchi@example.com"));
	}

	@Test
	void loginFailureReturnsInvalidCredentials() throws Exception {
		given(authService.login(any())).willThrow(new BusinessException(ResultCode.INVALID_CREDENTIALS));

		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"noonchi@example.com","password":"wrong1234"}"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.resultCode").value("INVALID_CREDENTIALS"));
	}

	@Test
	void refreshReturnsNewTokens() throws Exception {
		given(authService.refresh("refresh")).willReturn(new TokenResponse("access2", "refresh2"));

		mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"refresh"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.accessToken").value("access2"))
				.andExpect(jsonPath("$.data.refreshToken").value("refresh2"));
	}

	@Test
	void logoutReturnsOkWithEmptyData() throws Exception {
		mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"refreshToken":"refresh"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resultCode").value("OK"))
				.andExpect(jsonPath("$.msg").value(ResultCode.OK.getMessage()))
				.andExpect(jsonPath("$.data").doesNotExist());

		verify(authService).logout("refresh");
	}
}
