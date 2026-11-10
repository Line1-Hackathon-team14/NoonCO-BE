package com.noonco.analysis.service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.noonco.ai.LlmClient;
import com.noonco.ai.LlmOutputEvent;
import com.noonco.ai.LlmOutputParser;
import com.noonco.ai.LlmRequest;
import com.noonco.ai.LlmStream;
import com.noonco.analysis.dto.AnalysisStreamEvent;
import com.noonco.analysis.service.AnalysisService.AnalysisStart;
import com.noonco.global.file.FileStorage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 해설 생성 결과를 NDJSON 스트림으로 내보낸다.
 * 시작 검사(PENDING 생성)는 호출 스레드에서 끝내므로, 그 단계의 오류는 일반 JSON 에러 응답이 된다.
 * 스트림이 완료(COMPLETED 저장)되지 않고 끝나면 FAILED 로 저장해 다시 요청할 수 있게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisStreamService {

	private static final byte NEWLINE = '\n';

	private final AnalysisService analysisService;
	private final LlmClient llmClient;
	private final FileStorage fileStorage;
	private final JsonMapper jsonMapper;

	/**
	 * @throws com.noonco.global.exception.BusinessException RESOURCE_NOT_FOUND, ANALYSIS_ALREADY_COMPLETED, ANALYSIS_IN_PROGRESS
	 */
	public StreamingResponseBody stream(Long userId, Long materialId) {
		AnalysisStart start = analysisService.start(userId, materialId);
		return out -> run(start, out);
	}

	/**
	 * 응답이 이미 커밋된 뒤라 예외를 JSON 에러로 바꿀 수 없으므로, 여기서 로그만 남기고 스트림을 끝낸다.
	 * (error 이벤트 전송은 #20 범위)
	 */
	private void run(AnalysisStart start, OutputStream out) {
		boolean completed = false;
		LlmStream llmStream = null;
		try {
			write(out, AnalysisStreamEvent.started(start.analysisId(), start.difficulty()));
			write(out, AnalysisStreamEvent.progress(AnalysisStreamEvent.STAGE_ANALYZING));

			byte[] image = fileStorage.load(start.filePath()).getContentAsByteArray();
			llmStream = llmClient.stream(LlmRequest.ofImage(start.prompt().content(), image, start.mimeType()));

			StreamState state = new StreamState();
			LlmOutputParser parser = new LlmOutputParser(start.sourceType());
			for (String chunk : llmStream) {
				handle(out, parser.feed(chunk), state);
			}
			handle(out, parser.finish(), state);

			// 비동기 요청 제한 시간 초과 등으로 인터럽트되면 스트림이 조용히 끝나므로, 본문이 잘렸을 수 있다
			if (Thread.currentThread().isInterrupted() || llmStream.isClosed()) {
				log.warn("해설 스트림이 중단되었습니다: analysisId={}", start.analysisId());
				return;
			}
			if (state.meta == null) {
				// 판독 불가(Unreadable) 처리는 #20 범위
				log.warn("메타 없이 해설 스트림이 끝났습니다: analysisId={}", start.analysisId());
				return;
			}
			analysisService.complete(start.analysisId(), start.materialId(), state.meta.visualType(),
					state.meta.title(), state.summary.toString(), llmClient.model(), start.prompt().version());
			completed = true;
			write(out, AnalysisStreamEvent.done(start.analysisId()));
		} catch (IOException e) {
			log.info("해설 스트림 전송이 끊겼습니다: analysisId={}, completed={}, cause={}", start.analysisId(), completed,
					e.toString());
		} catch (RuntimeException e) {
			log.error("해설 스트림 처리 중 오류: analysisId={}", start.analysisId(), e);
		} finally {
			if (llmStream != null) {
				llmStream.close();
			}
			if (!completed) {
				failQuietly(start.analysisId());
			}
		}
	}

	private void handle(OutputStream out, List<LlmOutputEvent> events, StreamState state) throws IOException {
		for (LlmOutputEvent event : events) {
			switch (event) {
				case LlmOutputEvent.Meta meta -> {
					state.meta = meta;
					write(out, AnalysisStreamEvent.meta(meta.visualType(), meta.title()));
					write(out, AnalysisStreamEvent.progress(AnalysisStreamEvent.STAGE_GENERATING));
				}
				case LlmOutputEvent.Delta delta -> {
					state.summary.append(delta.text());
					write(out, AnalysisStreamEvent.delta(delta.text()));
				}
				case LlmOutputEvent.Unreadable unreadable -> {
					// #20 에서 처리한다. 지금은 완료하지 않고 끝내 FAILED 로 남긴다
				}
			}
		}
	}

	private void write(OutputStream out, AnalysisStreamEvent event) throws IOException {
		out.write(jsonMapper.writeValueAsBytes(event));
		out.write(NEWLINE);
		out.flush();
	}

	private void failQuietly(Long analysisId) {
		try {
			analysisService.fail(analysisId);
		} catch (RuntimeException e) {
			log.error("analysis 실패 상태를 저장하지 못했습니다: analysisId={}", analysisId, e);
		}
	}

	private static final class StreamState {

		private LlmOutputEvent.Meta meta;
		private final StringBuilder summary = new StringBuilder();
	}
}
