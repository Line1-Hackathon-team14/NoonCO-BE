package com.noonco.material.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.noonco.global.ResultCode;
import com.noonco.global.RsData;
import com.noonco.material.dto.MaterialUploadResponse;
import com.noonco.material.service.MaterialService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

@Tag(name = "자료", description = "촬영 이미지와 PDF 강의자료 업로드 (Authorization: Bearer {accessToken} 필요)")
@RestController
@RequestMapping("/api/v1/materials")
@RequiredArgsConstructor
public class MaterialController {

	private final MaterialService materialService;

	@Operation(summary = "촬영 이미지 업로드", description = """
			multipart/form-data 의 file 1개. image/jpeg, image/png, 최대 5MB.
			에러: INVALID_FILE_TYPE(400), FILE_TOO_LARGE(413)""")
	@PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<RsData<MaterialUploadResponse>> uploadImage(
			@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
			@RequestParam("file") MultipartFile file) {
		return ResponseEntity.status(ResultCode.CREATED.getStatus())
				.body(RsData.success(ResultCode.CREATED, materialService.uploadImage(userId, file)));
	}

	@Operation(summary = "PDF 업로드", description = """
			multipart/form-data 의 file 1개. application/pdf, 최대 5MB, 30쪽 이하.
			스캔 PDF 도 업로드할 수 있다. 검증에 실패한 파일은 저장하지 않는다.
			에러: INVALID_FILE_TYPE(400), FILE_TOO_LARGE(413), PDF_UNREADABLE(422, 손상·열람 암호), PDF_TOO_MANY_PAGES(422)""")
	@PostMapping(value = "/pdfs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<RsData<MaterialUploadResponse>> uploadPdf(
			@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
			@RequestParam("file") MultipartFile file) {
		return ResponseEntity.status(ResultCode.CREATED.getStatus())
				.body(RsData.success(ResultCode.CREATED, materialService.uploadPdf(userId, file)));
	}
}
