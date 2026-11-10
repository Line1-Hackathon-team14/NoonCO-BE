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

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/materials")
@RequiredArgsConstructor
public class MaterialController {

	private final MaterialService materialService;

	@PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<RsData<MaterialUploadResponse>> uploadImage(@AuthenticationPrincipal Long userId,
			@RequestParam("file") MultipartFile file) {
		return ResponseEntity.status(ResultCode.CREATED.getStatus())
				.body(RsData.success(ResultCode.CREATED, materialService.uploadImage(userId, file)));
	}
}
