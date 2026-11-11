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
import jakarta.servlet.http.HttpServletResponse;

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
			meta: {"type":"meta","visualType":"LINE","title":"..."} (title 은 자료 제목, PDF 는 visualType 없이 title 만 온다)
			delta: {"type":"delta","section":"SUMMARY"|"DETAIL","text":"..."}
			SUMMARY(한 번 탭, 전체 요약) delta 가 모두 나간 뒤 DETAIL(두 번 탭, 축·수치 상세) delta 가 나간다.
			상세가 없으면 DETAIL delta 는 오지 않는다(PDF 는 현재 상세 없음). 같은 section 의 text 를 순서대로 이어 붙이면 된다.
			started 이후 3초마다 {"type":"ping"} 줄이 섞여 올 수 있으며, 연결 유지용이므로 무시한다.
			실패·취소된 자료만 다시 요청할 수 있다. 스트림 시작 전 오류는 일반 JSON 에러로 응답한다.
			동시 실행 수를 넘으면 503 AI_BUSY 로 응답한다.""")
	@PostMapping
	public ResponseEntity<StreamingResponseBody> createAnalysis(
			@Parameter(hidden = true) @AuthenticationPrincipal Long userId, @PathVariable Long materialId,
			HttpServletResponse response) {
		StreamingResponseBody body = analysisStreamService.stream(userId, materialId);
		// Spring 7 이 StreamingResponseBody 에 넘기는 스트림은 flush 를 무시한다
		// (spring.http.response.flush.enabled 기본값 false → StreamUtils.nonFlushing).
		// 그대로 쓰면 이벤트가 끝날 때 한꺼번에 전송되므로, flush 가 동작하는 서블릿 출력 스트림에 직접 쓴다. (#39)
		return ResponseEntity.ok().contentType(NDJSON_UTF8).body(out -> body.writeTo(response.getOutputStream()));
	}
}
