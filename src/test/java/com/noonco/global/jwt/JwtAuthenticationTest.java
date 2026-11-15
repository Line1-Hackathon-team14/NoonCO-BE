package com.noonco.global.jwt;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.noonco.global.ResultCode;
import com.noonco.global.RsData;
import com.noonco.global.config.SecurityConfig;

@WebMvcTest
@ContextConfiguration(classes = { JwtAuthenticationTest.TestController.class, SecurityConfig.class, JwtTokenProvider.class })
@TestPropertySource(properties = "jwt.secret=" + JwtAuthenticationTest.SECRET)
class JwtAuthenticationTest {

	static final String SECRET = "test-secret-key-for-noonco-jwt-must-be-32-bytes-or-more";

	@RestController
	static class TestController {

		@GetMapping("/api/v1/test/me")
		RsData<Long> me(@AuthenticationPrincipal Long userId) {
			return RsData.success(userId);
		}

		@GetMapping("/api/v1/auth/ping")
		RsData<String> ping() {
			return RsData.success("pong");
		}
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtTokenProvider jwtTokenProvider;

	@Test
	void validTokenExposesCurrentUserId() throws Exception {
		String token = jwtTokenProvider.createAccessToken(42L);

		mockMvc.perform(get("/api/v1/test/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").value(42));
	}

	@Test
	void missingTokenReturnsUnauthorized() throws Exception {
		expectFailure(mockMvc.perform(get("/api/v1/test/me")).andExpect(status().isUnauthorized()), ResultCode.UNAUTHORIZED);
	}

	@Test
	void malformedTokenReturnsUnauthorized() throws Exception {
		expectFailure(mockMvc.perform(get("/api/v1/test/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
				.andExpect(status().isUnauthorized()), ResultCode.UNAUTHORIZED);
	}

	@Test
	void tokenSignedWithOtherKeyReturnsUnauthorized() throws Exception {
		JwtTokenProvider otherProvider = new JwtTokenProvider(
				new JwtProperties("another-secret-key-that-is-also-32-bytes-long!!", Duration.ofMinutes(30), Duration.ofDays(14)));
		String forged = otherProvider.createAccessToken(42L);

		expectFailure(mockMvc.perform(get("/api/v1/test/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
				.andExpect(status().isUnauthorized()), ResultCode.UNAUTHORIZED);
	}

	@Test
	void expiredTokenReturnsAccessTokenExpired() throws Exception {
		JwtTokenProvider expiredProvider = new JwtTokenProvider(new JwtProperties(SECRET, Duration.ofSeconds(-1), Duration.ofDays(14)));
		String expired = expiredProvider.createAccessToken(42L);

		expectFailure(mockMvc.perform(get("/api/v1/test/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
				.andExpect(status().isUnauthorized()), ResultCode.ACCESS_TOKEN_EXPIRED);
	}

	@Test
	void authPathIsAccessibleWithoutToken() throws Exception {
		mockMvc.perform(get("/api/v1/auth/ping"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").value("pong"));
	}

	@Test
	void authPathIgnoresExpiredToken() throws Exception {
		JwtTokenProvider expiredProvider = new JwtTokenProvider(new JwtProperties(SECRET, Duration.ofSeconds(-1), Duration.ofDays(14)));
		String expired = expiredProvider.createAccessToken(42L);

		mockMvc.perform(get("/api/v1/auth/ping").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").value("pong"));
	}

	private void expectFailure(ResultActions actions, ResultCode resultCode) throws Exception {
		actions.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.resultCode").value(resultCode.name()))
				.andExpect(jsonPath("$.msg").value(resultCode.getMessage()))
				.andExpect(jsonPath("$.data").doesNotExist());
	}
}
