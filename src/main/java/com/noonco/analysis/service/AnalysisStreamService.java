package com.noonco.analysis.service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.noonco.ai.LlmClient;
import com.noonco.ai.LlmOutputEvent;
import com.noonco.ai.LlmOutputParser;
import com.noonco.ai.LlmRequest;
import com.noonco.ai.LlmStream;
import com.noonco.analysis.AnalysisStreamProperties;
import com.noonco.analysis.dto.AnalysisStreamEvent;
import com.noonco.analysis.service.AnalysisService.AnalysisStart;
import com.noonco.global.ResultCode;
import com.noonco.global.exception.BusinessException;
import com.noonco.global.file.FileStorage;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 해설 생성 결과를 NDJSON 스트림으로 내보낸다.
 * 시작 검사(동시 실행 제한, PENDING 생성)는 호출 스레드에서 끝내므로, 그 단계의 오류는 일반 JSON 에러 응답이 된다.
 * 스트림이 완료(COMPLETED 저장)되지 않고 끝나면 FAILED 로 저장해 다시 요청할 수 있게 한다.
 */
@Slf4j
@Service
@EnableConfigurationProperties(AnalysisStreamProperties.class)
public class AnalysisStreamService {

	private static final byte NEWLINE = '\n';

	private final AnalysisService analysisService;
	private final LlmClient llmClient;
	private final FileStorage fileStorage;
	private final JsonMapper jsonMapper;
	private final Semaphore permits;
	private final long pingIntervalNanos;
	private final int summaryMaxLength;

	public AnalysisStreamService(AnalysisService analysisService, LlmClient llmClient, FileStorage fileStorage,
			JsonMapper jsonMapper, AnalysisStreamProperties properties) {
		this.analysisService = analysisService;
		this.llmClient = llmClient;
		this.fileStorage = fileStorage;
		this.jsonMapper = jsonMapper;
		this.permits = new Semaphore(properties.maxConcurrent());
		this.pingIntervalNanos = properties.pingInterval().toNanos();
		this.summaryMaxLength = properties.summaryMaxLength();
	}

	/**
	 * 실행 자리를 얻은 경우에만 PENDING 을 만든다. 얻은 자리는 스트림이 어떻게 끝나든 반환한다.
	 *
	 * @throws BusinessException AI_BUSY, RESOURCE_NOT_FOUND, ANALYSIS_ALREADY_COMPLETED, ANALYSIS_IN_PROGRESS
	 */
	public StreamingResponseBody stream(Long userId, Long materialId) {
		if (!permits.tryAcquire()) {
			throw new BusinessException(ResultCode.AI_BUSY);
		}
		AnalysisStart start;
		try {
			start = analysisService.start(userId, materialId);
		} catch (RuntimeException e) {
			permits.release();
			throw e;
		}
		return out -> run(start, out);
	}

	/**
	 * 응답이 이미 커밋된 뒤라 예외를 JSON 에러로 바꿀 수 없으므로, 여기서 로그만 남기고 스트림을 끝낸다.
	 * (error 이벤트 전송은 #20 범위)
	 */
	private void run(AnalysisStart start, OutputStream out) {
		StreamWriter writer = new StreamWriter(out);
		boolean completed = false;
		LlmStream llmStream = null;
		try {
			writer.write(AnalysisStreamEvent.started(start.analysisId(), start.difficulty()));
			writer.startPing();
			writer.write(AnalysisStreamEvent.progress(AnalysisStreamEvent.STAGE_ANALYZING));

			byte[] file = fileStorage.load(start.filePath()).getContentAsByteArray();
			llmStream = llmClient.stream(LlmRequest.ofFile(start.prompt().content(), file, start.mimeType()));
			writer.attach(llmStream);

			StreamState state = new StreamState();
			LlmOutputParser parser = new LlmOutputParser(start.sourceType(), summaryMaxLength);
			for (String chunk : llmStream) {
				handle(writer, parser.feed(chunk), state);
			}
			handle(writer, parser.finish(), state);

			// 비동기 요청 제한 시간 초과로 인터럽트되거나, ping 전송 실패(연결 끊김)로 LLM 스트림이 닫히면
			// 스트림이 조용히 끝나므로 본문이 잘렸을 수 있다
			if (Thread.currentThread().isInterrupted() || llmStream.isClosed()) {
				log.warn("해설 스트림이 중단되었습니다: analysisId={}, disconnected={}", start.analysisId(),
						writer.disconnected);
				return;
			}
			if (state.meta == null) {
				// 판독 불가(Unreadable) 처리는 #20 범위
				log.warn("메타 없이 해설 스트림이 끝났습니다: analysisId={}", start.analysisId());
				return;
			}
			analysisService.complete(start.analysisId(), start.materialId(), state.meta.visualType(),
					state.meta.title(), state.summary.toString(), state.detail.isEmpty() ? null : state.detail.toString(),
					llmClient.model(), start.prompt().version());
			completed = true;
			writer.write(AnalysisStreamEvent.done(start.analysisId()));
		} catch (IOException e) {
			log.info("해설 스트림 전송이 끊겼습니다: analysisId={}, completed={}, cause={}", start.analysisId(), completed,
					e.toString());
		} catch (RuntimeException e) {
			log.error("해설 스트림 처리 중 오류: analysisId={}", start.analysisId(), e);
		} finally {
			// 정리 중 예외가 나도 실행 자리는 반드시 반환한다
			try {
				writer.close();
				if (llmStream != null) {
					llmStream.close();
				}
				if (!completed) {
					failQuietly(start.analysisId());
				}
			} finally {
				permits.release();
			}
		}
	}

	private void handle(StreamWriter writer, List<LlmOutputEvent> events, StreamState state) throws IOException {
		for (LlmOutputEvent event : events) {
			switch (event) {
				case LlmOutputEvent.Meta meta -> {
					state.meta = meta;
					writer.write(AnalysisStreamEvent.meta(meta.visualType(), meta.title()));
					writer.write(AnalysisStreamEvent.progress(AnalysisStreamEvent.STAGE_GENERATING));
				}
				case LlmOutputEvent.Delta delta -> {
					// 보낸 텍스트를 그대로 모아 저장하므로 전송한 해설과 저장한 해설이 같다
					(delta.section() == LlmOutputEvent.Section.SUMMARY ? state.summary : state.detail).append(delta.text());
					writer.write(AnalysisStreamEvent.delta(delta.section().name(), delta.text()));
				}
				case LlmOutputEvent.Unreadable unreadable -> {
					// #20 에서 처리한다. 지금은 완료하지 않고 끝내 FAILED 로 남긴다
				}
			}
		}
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
		private final StringBuilder detail = new StringBuilder();
	}

	/**
	 * 본문 이벤트와 ping 을 같은 잠금 아래에서 한 줄씩 써서 줄이 섞이지 않게 한다.
	 * ping 은 스트림마다 가상 스레드 하나가 보내고, ping 전송이 실패하면 연결이 끊긴 것으로 보고 LLM 스트림을 닫는다.
	 * LLM 응답을 기다리는 동안에는 본문 쓰기가 없으므로, 이 단계의 연결 끊김은 ping 으로만 감지된다.
	 */
	private final class StreamWriter {

		private final OutputStream out;
		private final Object lock = new Object();
		private boolean closed;
		private volatile boolean disconnected;
		private volatile LlmStream llmStream;

		StreamWriter(OutputStream out) {
			this.out = out;
		}

		void write(AnalysisStreamEvent event) throws IOException {
			synchronized (lock) {
				writeLine(event);
			}
		}

		void startPing() {
			Thread.ofVirtual().name("analysis-ping").start(this::pingLoop);
		}

		/**
		 * 이미 연결 끊김이 감지됐으면 바로 닫는다. ping 스레드와 순서가 엇갈려도 둘 중 한쪽이 닫는다.
		 */
		void attach(LlmStream stream) {
			this.llmStream = stream;
			if (disconnected) {
				stream.close();
			}
		}

		/**
		 * 이후로는 ping 을 보내지 않는다. done 뒤에 ping 이 붙지 않도록 마지막 쓰기 직후 호출한다.
		 */
		void close() {
			synchronized (lock) {
				closed = true;
				lock.notifyAll();
			}
		}

		private void pingLoop() {
			synchronized (lock) {
				long next = System.nanoTime() + pingIntervalNanos;
				while (!closed) {
					long remaining = next - System.nanoTime();
					if (remaining > 0) {
						try {
							TimeUnit.NANOSECONDS.timedWait(lock, remaining);
						} catch (InterruptedException e) {
							return;
						}
						continue;
					}
					try {
						writeLine(AnalysisStreamEvent.ping());
					} catch (IOException e) {
						disconnected = true;
						break;
					}
					next += pingIntervalNanos;
				}
			}
			LlmStream stream = llmStream;
			if (disconnected && stream != null) {
				stream.close();
			}
		}

		private void writeLine(AnalysisStreamEvent event) throws IOException {
			out.write(jsonMapper.writeValueAsBytes(event));
			out.write(NEWLINE);
			out.flush();
		}
	}
}
