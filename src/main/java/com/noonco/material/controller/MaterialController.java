package com.noonco.material.controller;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.noonco.global.ResultCode;
import com.noonco.global.RsData;
import com.noonco.material.dto.MaterialCountResponse;
import com.noonco.material.dto.MaterialDetailResponse;
import com.noonco.material.dto.MaterialListRequest;
import com.noonco.material.dto.MaterialPageResponse;
import com.noonco.material.dto.MaterialUploadResponse;
import com.noonco.material.service.MaterialService;
import com.noonco.material.service.MaterialService.MaterialFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
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

	@Operation(summary = "학습 기록 목록", description = """
			완료된 해설이 있는 자료만 최신순으로 반환한다.
			Query: sourceType(IMAGE/PDF, 없으면 전체), page(0부터, 기본 0), size(1~50, 기본 20).
			에러: VALIDATION_FAILED(400, page·size 범위나 sourceType 값이 잘못됨)""")
	@GetMapping
	public RsData<MaterialPageResponse> getMaterials(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
			@ParameterObject @Valid @ModelAttribute MaterialListRequest request) {
		return RsData.success(materialService.getMaterials(userId, request));
	}

	@Operation(summary = "학습 기록 개수", description = "학습 기록 목록과 같은 조건으로 전체·사진·PDF 개수를 센다.")
	@GetMapping("/counts")
	public RsData<MaterialCountResponse> getCounts(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
		return RsData.success(materialService.getCounts(userId));
	}

	@Operation(summary = "학습 기록 상세", description = """
			최신 완료 해설(analysis)을 함께 반환하며, 없으면 analysis 는 null 이다. 스트림이 끊겼을 때 복구 조회에도 쓴다.
			에러: RESOURCE_NOT_FOUND(404, 없는·삭제된·타인 자료)""")
	@GetMapping("/{materialId}")
	public RsData<MaterialDetailResponse> getMaterial(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
			@PathVariable Long materialId) {
		return RsData.success(materialService.getMaterial(userId, materialId));
	}

	@Operation(summary = "원본 파일 조회", description = """
			JSON 이 아닌 원본 바이너리를 반환한다. Content-Type 은 파일 형식, Content-Disposition 은 inline.
			에러: RESOURCE_NOT_FOUND(404, 없는·삭제된·타인 자료)""")
	@GetMapping("/{materialId}/file")
	public ResponseEntity<Resource> getFile(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
			@PathVariable Long materialId) {
		MaterialFile file = materialService.getFile(userId, materialId);
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(file.mimeType()))
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
				.body(file.resource());
	}

	@Operation(summary = "자료 삭제", description = """
			자료를 삭제한다(soft delete). 이미 업로드한 PDF 를 "다른 파일 고르기"로 취소할 때도 쓴다.
			에러: RESOURCE_NOT_FOUND(404), ANALYSIS_IN_PROGRESS(409, 해설 생성 중)""")
	@DeleteMapping("/{materialId}")
	public ResponseEntity<Void> deleteMaterial(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
			@PathVariable Long materialId) {
		materialService.deleteMaterial(userId, materialId);
		return ResponseEntity.noContent().build();
	}
}
