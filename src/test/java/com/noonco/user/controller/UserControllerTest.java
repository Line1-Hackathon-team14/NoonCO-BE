package com.noonco.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.noonco.global.config.SecurityConfig;
import com.noonco.global.exception.GlobalExceptionHandler;
import com.noonco.global.jwt.JwtTokenProvider;
import com.noonco.user.dto.UpdateSettingsRequest;
import com.noonco.user.dto.UserResponse;
import com.noonco.user.dto.UserSettingsResponse;
import com.noonco.user.entity.Difficulty;
import com.noonco.user.service.UserService;

@WebMvcTest
@ContextConfiguration(classes = { UserController.class, GlobalExceptionHandler.class, SecurityConfig.class,
		JwtTokenProvider.class })
@TestPropertySource(properties = "jwt.secret=test-secret-key-for-noonco-jwt-must-be-32-bytes-or-more")
class UserControllerTest {

	private static final long USER_ID = 1L;

	@Autowired MockMvc mockMvc;
	@Autowired JwtTokenProvider jwtTokenProvider;

	@MockitoBean UserService userService;

	@Test
	void getMeReturnsUserAndSettings() throws Exception {
		given(userService.getMe(USER_ID)).willReturn(
				new UserResponse(USER_ID, "김눈치", "noonchi@example.com", Difficulty.NORMAL, new BigDecimal("1.0")));

		mockMvc.perform(authorized(get("/api/v1/users/me")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resultCode").value("OK"))
				.andExpect(jsonPath("$.data.name").value("김눈치"))
				.andExpect(jsonPath("$.data.email").value("noonchi@example.com"))
				.andExpect(jsonPath("$.data.difficulty").value("NORMAL"))
				.andExpect(jsonPath("$.data.speechRate").value(1.0));
	}

	@Test
	void getMeWithoutTokenReturnsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/users/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.resultCode").value("UNAUTHORIZED"));
	}

	@Test
	void updateSettingsPassesOnlySentFields() throws Exception {
		given(userService.updateSettings(eq(USER_ID), any()))
				.willReturn(new UserSettingsResponse(Difficulty.HARD, new BigDecimal("1.0")));

		mockMvc.perform(authorized(patch("/api/v1/users/me/settings")).content("""
						{"difficulty":"HARD"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.difficulty").value("HARD"))
				.andExpect(jsonPath("$.data.speechRate").value(1.0));

		verify(userService).updateSettings(USER_ID, new UpdateSettingsRequest(Difficulty.HARD, null));
	}

	@Test
	void updateSettingsRejectsEmptyBody() throws Exception {
		mockMvc.perform(authorized(patch("/api/v1/users/me/settings")).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.resultCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.data[0].message").value("변경할 설정을 입력해 주세요."));

		verifyNoInteractions(userService);
	}

	@ParameterizedTest
	@ValueSource(strings = { "0.4", "2.1", "1.25" })
	void updateSettingsRejectsInvalidSpeechRate(String speechRate) throws Exception {
		mockMvc.perform(authorized(patch("/api/v1/users/me/settings")).content("{\"speechRate\":" + speechRate + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.resultCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.data[0].field").value("speechRate"));

		verifyNoInteractions(userService);
	}

	@Test
	void updateSettingsRejectsUnknownDifficulty() throws Exception {
		mockMvc.perform(authorized(patch("/api/v1/users/me/settings")).content("""
						{"difficulty":"XX"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.resultCode").value("INVALID_REQUEST"));

		verifyNoInteractions(userService);
	}

	private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
		return request.header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(USER_ID))
				.contentType(MediaType.APPLICATION_JSON);
	}
}
