package com.noonco.analysis.service;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.noonco.ai.LlmClient;
import com.noonco.ai.LlmOutputEvent;
import com.noonco.ai.LlmOutputFormatException;
import com.noonco.ai.LlmOutputParser;
import com.noonco.ai.LlmProperties;
import com.noonco.ai.LlmRequest;
import com.noonco.ai.LlmStream;
import com.noonco.analysis.AnalysisStreamProperties;
import com.noonco.analysis.dto.AnalysisStreamEvent;
import com.noonco.analysis.entity.AnalysisStatus;
import com.noonco.analysis.service.AnalysisService.AnalysisStart;
import com.noonco.global.ResultCode;
import com.noonco.global.exception.BusinessException;
import com.noonco.global.file.FileStorage;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 해설 생성 결과를 NDJSON 스트림으로 내보낸다.
 * 시작 검사(동시 실행 제한, PENDING 생성)는 호출 스레드에서 끝내므로, 그 단계의 오류는 일반 JSON 에러 응답이 된다.
 * 스트림 도중의 실패는 HTTP 상태가 이미 200 이므로 analysis 상태를 저장한 뒤 error 줄을 마지막 줄로 보내 알린다.
 * 연결이 끊긴 경우에는 보낼 곳이 없으므로 FAILED 로만 저장한다. 어느 쪽이든 다시 요청할 수 있다.
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
	// 분석 제한 시간. 넘으면 LLM 호출을 끊고 AI_TIMEOUT 을 보낸다. PENDING 을 진행 중으로 보는 시간과 같다
	private final long analysisTimeoutNanos;

	/**
	 * @param asyncRequestTimeout 비동기 요청 제한 시간. 분석 제한 시간보다 길어야 AI_TIMEOUT 을 보낼 수 있다
	 */
	public AnalysisStreamService(AnalysisService analysisService, LlmClient llmClient, FileStorage fileStorage,
			JsonMapper jsonMapper, AnalysisStreamProperties properties, LlmProperties llmProperties,
			@Value("${spring.mvc.async.request-timeout:#{null}}") Duration asyncRequestTimeout) {
		Duration analysisTimeout = llmProperties.gemini().timeout();
		checkTimeouts(analysisTimeout, asyncRequestTimeout);
		this.analysisService = analysisService;
		this.llmClient = llmClient;
		this.fileStorage = fileStorage;
		this.jsonMapper = jsonMapper;
		this.permits = new Semaphore(properties.maxConcurrent());
		this.pingIntervalNanos = properties.pingInterval().toNanos();
		this.summaryMaxLength = properties.summaryMaxLength();
		this.analysisTimeoutNanos = analysisTimeout.toNanos();
	}

	/**
	 * 비동기 요청 제한이 먼저 걸리면 응답이 끝나 버려 error 를 보낼 수 없으므로 기동 시점에 막는다.
	 * 0 이하(제한 없음)는 검사하지 않는다.
	 */
	static void checkTimeouts(Duration analysisTimeout, Duration asyncRequestTimeout) {
		if (asyncRequestTimeout != null && asyncRequestTimeout.isPositive()
				&& asyncRequestTimeout.compareTo(analysisTimeout) <= 0) {
			throw new IllegalStateException("spring.mvc.async.request-timeout(" + asyncRequestTimeout
					+ ") 은 분석 제한 시간 llm.gemini.timeout(" + analysisTimeout + ") 보다 길어야 합니다.");
		}
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
	 * 응답이 이미 커밋된 뒤라 예외가 밖으로 나가면 전역 예외 처리가 커밋된 응답에 JSON 에러를 쓰려 한다.
	 * 그래서 모든 예외를 여기서 처리하고 정상 반환한다.
	 */
	private void run(AnalysisStart start, OutputStream out) {
		StreamWriter writer = new StreamWriter(start.analysisId(), start.materialId(), out,
				System.nanoTime() + analysisTimeoutNanos);
		StreamState state = new StreamState();
		try {
			ResultCode failure = generate(start, writer, state);
			if (failure != null) {
				sendFailure(start.analysisId(), start.materialId(), writer, failure);
			}
		} catch (IOException e) {
			log.info("해설 스트림 연결이 끊겼습니다: analysisId={}, completed={}, cause={}", start.analysisId(),
					state.completed, e.toString());
		} catch (RuntimeException e) {
			if (writer.disconnected || Thread.currentThread().isInterrupted()) {
				log.info("연결이 끊긴 해설 스트림이 중단되었습니다: analysisId={}, cause={}", start.analysisId(), e.toString());
			} else if (state.completed) {
				log.error("해설 완료 후 스트림 처리 중 오류: analysisId={}", start.analysisId(), e);
			} else {
				ResultCode failure = writer.isPastDeadline() ? ResultCode.AI_TIMEOUT : ResultCode.AI_FAILED;
				if (e instanceof LlmOutputFormatException) {
					// 파서 예외 메시지에는 모델 출력(메타 줄)이 들어 있으므로 WARN 에는 넣지 않고 DEBUG 로만 남긴다
					log.debug("모델 출력 형식 오류 상세: analysisId={}, message={}", start.analysisId(), e.getMessage());
					log.warn("모델 출력 형식이 잘못되었습니다: analysisId={}, {}", start.analysisId(),
							outputSummary(start.analysisId(), state, writer.llmStream));
				} else {
					log.error("해설 생성 중 오류: analysisId={}, failure={}", start.analysisId(), failure, e);
				}
				sendFailure(start.analysisId(), start.materialId(), writer, failure);
			}
		} finally {
			// 정리 중 예외가 나도 실행 자리는 반드시 반환하고, 예외는 밖으로 내보내지 않는다.
			// LLM 스트림 close 는 SDK 잠금에 막힐 수 있으므로 자리를 반환한 뒤에 한다
			try {
				writer.close();
				if (!state.completed) {
					saveFailureQuietly(start.analysisId(), start.materialId(), AnalysisStatus.FAILED);
				}
			} finally {
				writer.releasePermit();
				writer.closeLlmStream();
			}
		}
	}

	/**
	 * done 을 보냈으면 null, 실패를 알려야 하면 그 코드를 반환한다.
	 *
	 * @throws IOException 연결이 끊겨 더 보낼 수 없을 때 (본문 쓰기 실패, ping 실패 감지, 비동기 요청 제한 시간 초과로 인한 인터럽트).
	 *                     입력 파일 읽기 실패는 여기에 해당하지 않고 AI_FAILED 를 반환한다
	 */
	private ResultCode generate(AnalysisStart start, StreamWriter writer, StreamState state) throws IOException {
		writer.write(AnalysisStreamEvent.started(start.analysisId(), start.difficulty()));
		writer.startWatchdog();
		writer.write(AnalysisStreamEvent.progress(AnalysisStreamEvent.STAGE_ANALYZING));

		byte[] file;
		try {
			file = fileStorage.load(start.filePath()).getContentAsByteArray();
		} catch (IOException e) {
			// 입력 파일 읽기 실패다. 출력 연결은 살아 있으므로 연결 끊김으로 처리하지 않고 실패를 알린다
			log.error("해설할 자료 파일을 읽지 못했습니다: analysisId={}, materialId={}", start.analysisId(),
					start.materialId(), e);
			return ResultCode.AI_FAILED;
		}
		LlmStream llmStream = llmClient.stream(LlmRequest.ofFile(start.prompt().content(), file, start.mimeType()));
		writer.attach(llmStream);

		LlmOutputParser parser = new LlmOutputParser(start.sourceType(), summaryMaxLength);
		for (String chunk : llmStream) {
			state.received(chunk);
			handle(writer, parser.feed(chunk), state);
			if (state.unreadable) {
				// 판독 불가면 나머지 출력은 쓰지 않으므로 더 받지 않는다
				break;
			}
		}

		// 연결 끊김이나 시간 초과로 LLM 스트림이 닫히면 순회가 조용히 끝나므로 출력이 잘렸을 수 있다
		writer.checkConnected();
		if (writer.timedOut) {
			log.warn("해설 생성이 제한 시간을 넘겨 중단했습니다: analysisId={}", start.analysisId());
			return ResultCode.AI_TIMEOUT;
		}
		// 출력이 잘리지 않았을 때만 마무리한다. 메타 줄이 줄바꿈 없이 끝났으면 메타·판독 불가 이벤트가 여기서야 나오고,
		// 문장 단위로 보류 중이던 마지막 요약도 여기서 나오므로 판정·저장은 모두 이 뒤에 한다
		handle(writer, parser.finish(), state);
		if (state.unreadable) {
			log.info("이미지를 판독할 수 없다는 응답입니다: analysisId={}", start.analysisId());
			return ResultCode.UNREADABLE_IMAGE;
		}
		if (state.meta == null) {
			// 파서는 메타 줄이 없거나 잘못되면 예외를 던지므로 여기에 오지 않아야 한다. 오면 원인 진단용 정보를 남긴다
			log.warn("메타 없이 해설 스트림이 끝났습니다: analysisId={}, unreadable={}, {}", start.analysisId(),
					state.unreadable, outputSummary(start.analysisId(), state, llmStream));
			return ResultCode.AI_FAILED;
		}
		if (state.summary.toString().isBlank()) {
			// 요약(한 번 탭)이 기본 해설이므로, 메타만 있거나 상세만 있는 출력은 완료로 저장하지 않는다 (이미지·PDF 공통)
			log.warn("요약 없이 해설 스트림이 끝났습니다: analysisId={}, detailLength={}, {}", start.analysisId(),
					state.detail.length(), outputSummary(start.analysisId(), state, llmStream));
			return ResultCode.AI_FAILED;
		}

		boolean saved = analysisService.complete(start.analysisId(), start.materialId(), state.meta.visualType(),
				state.meta.title(), state.summary.toString(), state.detail.isEmpty() ? null : state.detail.toString(),
				llmStream.model(), start.prompt().version());
		if (!saved) {
			log.warn("같은 자료에 더 새 해설 요청이 있거나 이미 실패로 저장돼 결과를 저장하지 않았습니다: analysisId={}, materialId={}",
					start.analysisId(), start.materialId());
			return ResultCode.AI_FAILED;
		}
		state.completed = true;
		writer.writeLast(AnalysisStreamEvent.done(start.analysisId()));
		return null;
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
				case LlmOutputEvent.Unreadable unreadable -> state.unreadable = true;
			}
		}
	}

	/**
	 * LLM 호출을 먼저 끊고, 상태를 저장한 뒤 error 를 보낸다.
	 * 저장을 먼저 해야 error 를 받은 클라이언트가 곧바로 다시 요청해도 진행 중(ANALYSIS_IN_PROGRESS)으로 막히지 않는다.
	 * error 의 status 는 저장된 상태라는 약속이므로, 저장에 실패하면 error 없이 끝낸다(클라이언트에는 중단된 스트림으로 보인다).
	 * 그 경우 analysis 는 PENDING 으로 남을 수 있어, run 의 정리 단계에서 FAILED 저장을 한 번 더 시도한다.
	 */
	private void sendFailure(Long analysisId, Long materialId, StreamWriter writer, ResultCode failure) {
		AnalysisStatus status = failure == ResultCode.UNREADABLE_IMAGE ? AnalysisStatus.UNREADABLE : AnalysisStatus.FAILED;
		writer.closeLlmStream();
		if (!saveFailureQuietly(analysisId, materialId, status)) {
			log.warn("실패 상태를 저장하지 못해 error 없이 해설 스트림을 끝냅니다: analysisId={}, failure={}", analysisId, failure);
			return;
		}
		try {
			writer.writeLast(AnalysisStreamEvent.error(failure, status));
		} catch (IOException e) {
			log.info("해설 스트림 error 를 보내지 못했습니다: analysisId={}, failure={}, cause={}", analysisId, failure,
					e.toString());
		}
	}

	/**
	 * 아직 PENDING 일 때만 바뀌므로, 이미 저장한 실패 상태나 완료 상태를 덮어쓰지 않는다.
	 *
	 * @return 저장 중 오류가 없었으면 true
	 */
	private boolean saveFailureQuietly(Long analysisId, Long materialId, AnalysisStatus status) {
		try {
			if (status == AnalysisStatus.UNREADABLE) {
				analysisService.markUnreadable(analysisId);
			} else {
				analysisService.fail(analysisId, materialId);
			}
			return true;
		} catch (RuntimeException e) {
			log.error("analysis 실패 상태를 저장하지 못했습니다: analysisId={}, status={}", analysisId, status, e);
			return false;
		}
	}

	/**
	 * 진단 로그용 요약: 받은 텍스트 총 글자 수, 응답 종료 사유·안전 차단 정보(제공될 때).
	 * 모델 출력에는 사용자 자료 내용이 담길 수 있으므로 출력 앞부분은 넣지 않고 DEBUG 로만 따로 남긴다.
	 */
	private static String outputSummary(Long analysisId, StreamState state, LlmStream llmStream) {
		if (log.isDebugEnabled()) {
			log.debug("해설 스트림 모델 출력 앞부분: analysisId={}, head=\"{}\"", analysisId, escapeNewlines(state.head));
		}
		String finishInfo = llmStream == null ? null : llmStream.finishInfo();
		return "receivedLength=" + state.receivedLength + ", finishInfo=" + finishInfo;
	}

	private static String escapeNewlines(CharSequence text) {
		return text.toString().replace("\r", "\\r").replace("\n", "\\n");
	}

	private static final class StreamState {

		// 진단 로그에 남길 받은 텍스트 앞부분 길이(Unicode code point 수)
		private static final int HEAD_LENGTH = 100;

		private LlmOutputEvent.Meta meta;
		private boolean unreadable;
		private boolean completed;
		private final StringBuilder summary = new StringBuilder();
		private final StringBuilder detail = new StringBuilder();
		// LLM 에서 받은 원문의 총 글자 수(Unicode code point 수)와 앞부분
		private long receivedLength;
		private final StringBuilder head = new StringBuilder();

		void received(String chunk) {
			int length = chunk.codePointCount(0, chunk.length());
			int headRemaining = HEAD_LENGTH - (int)Math.min(receivedLength, HEAD_LENGTH);
			if (headRemaining > 0) {
				head.append(chunk, 0, chunk.offsetByCodePoints(0, Math.min(headRemaining, length)));
			}
			receivedLength += length;
		}
	}

	/**
	 * 본문 이벤트와 ping 을 같은 잠금 아래에서 한 줄씩 써서 줄이 섞이지 않게 한다.
	 * 스트림마다 가상 스레드 하나(watchdog)가 ping 을 보내고 분석 제한 시간을 지킨다.
	 * ping 전송이 실패하면 연결이 끊긴 것으로, 제한 시간이 지나면 시간 초과로 보고 LLM 스트림을 닫아 호출을 중단한다.
	 * LLM 응답을 기다리는 동안에는 본문 쓰기가 없으므로, 이 단계의 연결 끊김은 ping 으로만 감지된다.
	 * Gemini SDK 의 ResponseStream.close() 는 응답 읽기와 같은 잠금을 써서 SDK 제한 시간까지 막힐 수 있고,
	 * 그동안 스트림 스레드도 읽기에서 풀려나지 않는다. 그래서 ping 으로 끊김을 감지하면 watchdog 이 FAILED 저장과
	 * 실행 자리 반환을 먼저 하고 LLM 스트림을 닫는다. 실행 자리는 watchdog 과 스트림 스레드 중 먼저 온 쪽이 한 번만 반환한다.
	 */
	private final class StreamWriter {

		private final Long analysisId;
		private final Long materialId;
		private final OutputStream out;
		private final long deadline;
		private final Object lock = new Object();
		private final AtomicBoolean permitReleased = new AtomicBoolean();
		private boolean closed;
		private volatile boolean disconnected;
		private volatile boolean timedOut;
		private volatile LlmStream llmStream;

		StreamWriter(Long analysisId, Long materialId, OutputStream out, long deadline) {
			this.analysisId = analysisId;
			this.materialId = materialId;
			this.out = out;
			this.deadline = deadline;
		}

		/**
		 * 여러 번 불려도 실행 자리는 한 번만 반환한다.
		 */
		void releasePermit() {
			if (permitReleased.compareAndSet(false, true)) {
				permits.release();
			}
		}

		void write(AnalysisStreamEvent event) throws IOException {
			synchronized (lock) {
				writeLine(event);
			}
		}

		/**
		 * 마지막 줄(done 또는 error)을 쓰고 같은 잠금 안에서 닫으므로, 그 뒤에 ping 이 붙지 않는다.
		 */
		void writeLast(AnalysisStreamEvent event) throws IOException {
			synchronized (lock) {
				try {
					writeLine(event);
				} finally {
					closeLocked();
				}
			}
		}

		void startWatchdog() {
			Thread.ofVirtual().name("analysis-watchdog").start(this::watchLoop);
		}

		/**
		 * 이미 연결 끊김이나 시간 초과가 감지됐으면 바로 닫는다. watchdog 과 순서가 엇갈려도 둘 중 한쪽이 닫는다.
		 */
		void attach(LlmStream stream) {
			this.llmStream = stream;
			if (disconnected || timedOut) {
				stream.close();
			}
		}

		/**
		 * @throws IOException ping 으로 연결 끊김이 감지됐거나, 비동기 요청 제한 시간 초과로 스레드가 인터럽트됐을 때
		 */
		void checkConnected() throws IOException {
			if (disconnected) {
				throw new IOException("ping 전송 실패로 연결 끊김을 감지했습니다.");
			}
			if (Thread.currentThread().isInterrupted()) {
				throw new InterruptedIOException("해설 스트림 스레드가 인터럽트되었습니다.");
			}
		}

		/**
		 * OkHttp 호출 제한 시간처럼 watchdog 과 거의 같은 때 걸린 시간 초과도 시간 초과로 본다.
		 */
		boolean isPastDeadline() {
			return timedOut || System.nanoTime() - deadline >= 0;
		}

		/**
		 * 이후로는 ping 을 보내지 않는다.
		 */
		void close() {
			synchronized (lock) {
				closeLocked();
			}
		}

		void closeLlmStream() {
			LlmStream stream = llmStream;
			if (stream == null) {
				return;
			}
			try {
				stream.close();
			} catch (RuntimeException e) {
				log.warn("LLM 스트림을 닫지 못했습니다: cause={}", e.toString());
			}
		}

		private void closeLocked() {
			closed = true;
			lock.notifyAll();
		}

		private void watchLoop() {
			synchronized (lock) {
				long nextPing = System.nanoTime() + pingIntervalNanos;
				while (!closed) {
					long now = System.nanoTime();
					if (now - deadline >= 0) {
						timedOut = true;
						break;
					}
					long wakeAt = nextPing - deadline < 0 ? nextPing : deadline;
					long remaining = wakeAt - now;
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
					nextPing += pingIntervalNanos;
				}
			}
			if (disconnected) {
				// 스트림 스레드는 LLM 응답 읽기에 막혀 있을 수 있으므로 기다리지 않는다.
				// FAILED 를 먼저 저장해야 같은 자료를 다시 요청해도 진행 중(ANALYSIS_IN_PROGRESS)으로 막히지 않는다
				log.info("ping 전송 실패로 연결 끊김을 감지해 실행 자리를 먼저 반환합니다: analysisId={}", analysisId);
				try {
					saveFailureQuietly(analysisId, materialId, AnalysisStatus.FAILED);
				} finally {
					releasePermit();
				}
			}
			if (disconnected || timedOut) {
				closeLlmStream();
			}
		}

		private void writeLine(AnalysisStreamEvent event) throws IOException {
			out.write(jsonMapper.writeValueAsBytes(event));
			out.write(NEWLINE);
			out.flush();
		}
	}
}
