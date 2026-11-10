package com.noonco.analysis.controller;

import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.noonco.analysis.service.AnalysisStreamService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

@Tag(name = "해설", description = "AI 해설 생성 (Authorization: Bearer {accessToken} 필요)")
@RestController
@RequestMapping("/api/v1/materials/{materialId}/analyses")
@RequiredArgsConstructor
public class AnalysisController {

	private static final MediaType NDJSON_UTF8 = new MediaType("application", "x-ndjson", StandardCharsets.UTF_8);

	private final AnalysisStreamService analysisStreamService;

	@Operation(summary = "해설 생성 스트림", description = """
			사용자 설정 난이도로 해설을 만들어 NDJSON(한 줄에 JSON 하나)으로 보낸다.
			이벤트 순서: started → progress(ANALYZING) → meta → progress(GENERATING) → delta(여러 줄) → done.
			실패·취소된 자료만 다시 요청할 수 있다. 스트림 시작 전 오류는 일반 JSON 에러로 응답한다.""")
	@PostMapping
	public ResponseEntity<StreamingResponseBody> createAnalysis(
			@Parameter(hidden = true) @AuthenticationPrincipal Long userId, @PathVariable Long materialId) {
		StreamingResponseBody body = analysisStreamService.stream(userId, materialId);
		return ResponseEntity.ok().contentType(NDJSON_UTF8).body(body);
	}
}
