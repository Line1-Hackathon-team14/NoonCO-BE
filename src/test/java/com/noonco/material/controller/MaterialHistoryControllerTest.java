package com.noonco.material.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import com.noonco.analysis.entity.VisualType;
import com.noonco.global.ResultCode;
import com.noonco.global.config.SecurityConfig;
import com.noonco.global.exception.BusinessException;
import com.noonco.global.exception.GlobalExceptionHandler;
import com.noonco.global.jwt.JwtTokenProvider;
import com.noonco.material.dto.MaterialCountResponse;
import com.noonco.material.dto.MaterialListRequest;
import com.noonco.material.dto.MaterialPageResponse;
import com.noonco.material.dto.MaterialSummaryResponse;
import com.noonco.material.entity.SourceType;
import com.noonco.material.service.MaterialService;
import com.noonco.user.entity.Difficulty;

@WebMvcTest
@ContextConfiguration(classes = { MaterialController.class, GlobalExceptionHandler.class, SecurityConfig.class,
		JwtTokenProvider.class })
@TestPropertySource(properties = "jwt.secret=test-secret-key-for-noonco-jwt-must-be-32-bytes-or-more")
class MaterialHistoryControllerTest {

	private static final long USER_ID = 1L;

	@Autowired MockMvc mockMvc;
	@Autowired JwtTokenProvider jwtTokenProvider;

	@MockitoBean MaterialService materialService;

	@Test
	void listReturnsSpecShape() throws Exception {
		given(materialService.getMaterials(eq(USER_ID), any())).willReturn(new MaterialPageResponse(
				List.of(new MaterialSummaryResponse(11L, SourceType.PDF, "경제학_3주차_수요와공급", null, 12,
						Difficulty.NORMAL, LocalDateTime.of(2026, 11, 9, 14, 32, 0))),
				0, 20, 1, false));

		mockMvc.perform(get("/api/v1/materials").param("sourceType", "PDF").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(content().json("""
						{
						  "resultCode": "OK",
						  "msg": "요청이 성공했습니다.",
						  "data": {
						    "content": [
						      { "materialId": 11, "sourceType": "PDF", "title": "경제학_3주차_수요와공급", "visualType": null,
						        "pageCount": 12, "difficulty": "NORMAL", "createdAt": "2026-11-09T14:32:00" }
						    ],
						    "page": 0, "size": 20, "totalElements": 1, "hasNext": false
						  }
						}""", JsonCompareMode.STRICT));

		verify(materialService).getMaterials(USER_ID, new MaterialListRequest(SourceType.PDF, null, null));
	}

	@ParameterizedTest
	@CsvSource({ "size, 51", "size, 0", "page, -1", "sourceType, ABC" })
	void invalidListQueryReturnsValidationFailed(String name, String value) throws Exception {
		mockMvc.perform(get("/api/v1/materials").param(name, value).header("Authorization", bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.resultCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.data[0].field").value(name));

		verifyNoInteractions(materialService);
	}

	@Test
	void countsReturnsSpecShape() throws Exception {
		given(materialService.getCounts(USER_ID)).willReturn(new MaterialCountResponse(12, 8, 4));

		mockMvc.perform(get("/api/v1/materials/counts").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(content().json("""
						{ "resultCode": "OK", "msg": "요청이 성공했습니다.", "data": { "total": 12, "image": 8, "pdf": 4 } }""",
						JsonCompareMode.STRICT));
	}

	@Test
	void detailNotFoundReturns404() throws Exception {
		given(materialService.getMaterial(USER_ID, 99L)).willThrow(new BusinessException(ResultCode.RESOURCE_NOT_FOUND));

		mockMvc.perform(get("/api/v1/materials/99").header("Authorization", bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.resultCode").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	void fileReturnsRawBytesInline() throws Exception {
		byte[] bytes = "%PDF-1.7\n".getBytes();
		given(materialService.getFile(USER_ID, 11L))
				.willReturn(new MaterialService.MaterialFile(new ByteArrayResource(bytes), "application/pdf"));

		mockMvc.perform(get("/api/v1/materials/11/file").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
				.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline"))
				.andExpect(content().bytes(bytes));
	}

	@Test
	void deleteReturnsNoContent() throws Exception {
		mockMvc.perform(delete("/api/v1/materials/11").header("Authorization", bearer()))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		verify(materialService).deleteMaterial(USER_ID, 11L);
	}

	@Test
	void deleteInProgressReturnsConflict() throws Exception {
		willThrow(new BusinessException(ResultCode.ANALYSIS_IN_PROGRESS))
				.given(materialService).deleteMaterial(USER_ID, 11L);

		mockMvc.perform(delete("/api/v1/materials/11").header("Authorization", bearer()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.resultCode").value("ANALYSIS_IN_PROGRESS"));
	}

	@Test
	void listWithoutTokenReturnsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/materials"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.resultCode").value("UNAUTHORIZED"));

		verifyNoInteractions(materialService);
	}

	private String bearer() {
		return "Bearer " + jwtTokenProvider.createAccessToken(USER_ID);
	}
}
