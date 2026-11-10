package com.noonco.material.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import com.noonco.global.ResultCode;
import com.noonco.global.config.SecurityConfig;
import com.noonco.global.exception.BusinessException;
import com.noonco.global.exception.GlobalExceptionHandler;
import com.noonco.global.jwt.JwtTokenProvider;
import com.noonco.material.dto.MaterialUploadResponse;
import com.noonco.material.entity.SourceType;
import com.noonco.material.service.MaterialService;

@WebMvcTest
@ContextConfiguration(classes = { MaterialController.class, GlobalExceptionHandler.class, SecurityConfig.class,
		JwtTokenProvider.class })
@TestPropertySource(properties = "jwt.secret=test-secret-key-for-noonco-jwt-must-be-32-bytes-or-more")
class MaterialControllerTest {

	private static final long USER_ID = 1L;
	private static final String UPLOAD_PATH = "/api/v1/materials/images";
	private static final byte[] PNG_BYTES = {(byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};

	@Autowired MockMvc mockMvc;
	@Autowired JwtTokenProvider jwtTokenProvider;

	@MockitoBean MaterialService materialService;

	@Test
	void uploadImageReturnsCreatedWithSpecFieldsOnly() throws Exception {
		given(materialService.uploadImage(eq(USER_ID), any())).willReturn(new MaterialUploadResponse(
				10L, SourceType.IMAGE, null, PNG_BYTES.length, null, LocalDateTime.of(2026, 11, 10, 22, 30, 0)));

		// STRICT 비교로 명세에 없는 필드가 섞이거나 null 필드가 빠지면 실패한다
		mockMvc.perform(multipart(UPLOAD_PATH).file(png()).header("Authorization", bearer()))
				.andExpect(status().isCreated())
				.andExpect(content().json("""
						{
						  "resultCode": "CREATED",
						  "msg": "생성되었습니다.",
						  "data": {
						    "materialId": 10,
						    "sourceType": "IMAGE",
						    "title": null,
						    "fileSize": 12,
						    "pageCount": null,
						    "createdAt": "2026-11-10T22:30:00"
						  }
						}""", JsonCompareMode.STRICT));

		ArgumentCaptor<MultipartFile> captor = ArgumentCaptor.forClass(MultipartFile.class);
		verify(materialService).uploadImage(eq(USER_ID), captor.capture());
		assertThat(captor.getValue().getOriginalFilename()).isEqualTo("chart.png");
		assertThat(captor.getValue().getBytes()).isEqualTo(PNG_BYTES);
	}

	@Test
	void invalidFileTypeReturnsBadRequest() throws Exception {
		given(materialService.uploadImage(eq(USER_ID), any()))
				.willThrow(new BusinessException(ResultCode.INVALID_FILE_TYPE));

		mockMvc.perform(multipart(UPLOAD_PATH)
						.file(new MockMultipartFile("file", "doc.pdf", "application/pdf", "%PDF-1.7\n".getBytes()))
						.header("Authorization", bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.resultCode").value("INVALID_FILE_TYPE"))
				.andExpect(jsonPath("$.msg").value(ResultCode.INVALID_FILE_TYPE.getMessage()))
				.andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	void uploadWithoutTokenReturnsUnauthorized() throws Exception {
		mockMvc.perform(multipart(UPLOAD_PATH).file(png()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.resultCode").value("UNAUTHORIZED"));

		verifyNoInteractions(materialService);
	}

	private static MockMultipartFile png() {
		return new MockMultipartFile("file", "chart.png", "image/png", PNG_BYTES);
	}

	private String bearer() {
		return "Bearer " + jwtTokenProvider.createAccessToken(USER_ID);
	}
}
