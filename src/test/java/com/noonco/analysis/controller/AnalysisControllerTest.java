package com.noonco.analysis.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.AbstractResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.noonco.ai.LlmClient;
import com.noonco.ai.LlmInput;
import com.noonco.ai.LlmRequest;
import com.noonco.ai.LlmStream;
import com.noonco.ai.PromptLoader;
import com.noonco.analysis.entity.Analysis;
import com.noonco.analysis.entity.AnalysisStatus;
import com.noonco.analysis.entity.VisualType;
import com.noonco.analysis.repository.AnalysisRepository;
import com.noonco.analysis.service.AnalysisService;
import com.noonco.analysis.service.AnalysisStreamService;
import com.noonco.global.ResultCode;
import com.noonco.global.exception.BusinessException;
import com.noonco.global.file.FileStorage;
import com.noonco.global.jwt.JwtTokenProvider;
import com.noonco.material.entity.Material;
import com.noonco.material.entity.SourceType;
import com.noonco.material.repository.MaterialRepository;
import com.noonco.user.entity.Difficulty;
import com.noonco.user.entity.User;
import com.noonco.user.entity.UserSettings;
import com.noonco.user.repository.UserRepository;
import com.noonco.user.repository.UserSettingsRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 로컬 DB 와 Fake LLM 으로 해설 스트림 전체 흐름을 확인한다.
 * 스트림은 다른 스레드에서 돌고 트랜잭션을 나눠 커밋하므로 테스트 트랜잭션 없이 실행하고, 만든 데이터는 끝나고 지운다.
 */
// 동시 실행 수를 1로 두어, 앞선 스트림이 자리를 반환하지 않으면 다음 요청이 AI_BUSY 로 실패하게 한다
@SpringBootTest(properties = { "llm.provider=fake", "llm.fake.chunk-delay=1ms", "llm.gemini.timeout=60s",
		"analysis.stream.max-concurrent=1" })
@AutoConfigureMockMvc
class AnalysisControllerTest {

	private static final Path STORAGE_DIR = createTempDir();
	private static final byte[] PNG_BYTES = {(byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};
	private static final String STORED_KEY = UUID.randomUUID() + ".png";
	private static final byte[] PDF_BYTES = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
	private static final String STORED_PDF_KEY = UUID.randomUUID() + ".pdf";
	// FakeLlmClient 응답에서 첫 줄 메타 JSON 과 그 뒤 줄바꿈을 뺀 본문. 이미지는 구분선 줄과 그 앞뒤 줄바꿈도 뺀다
	private static final String FAKE_TITLE = "월별 방문자 수 추이";
	private static final String FAKE_PDF_TITLE = "월별 방문자 수 보고서";
	private static final String FAKE_SUMMARY = """
			이 자료는 1월부터 6월까지의 월별 방문자 수를 나타낸 꺾은선 그래프입니다.
			가로축은 월, 세로축은 방문자 수이며, 전체적으로 꾸준히 증가하는 흐름을 보입니다.
			가장 낮은 달은 1월로 약 1,200명이고, 가장 높은 달은 6월로 약 3,400명입니다.""";
	private static final String FAKE_DETAIL = """
			가로축은 1월부터 6월까지 한 달 간격이고, 세로축은 방문자 수(명)로 0명부터 4,000명까지 1,000명 간격의 눈금이 있습니다.
			최솟값은 1월의 약 1,200명, 최댓값은 6월의 약 3,400명입니다.
			2월 약 1,500명, 3월 약 1,900명, 4월 약 2,300명, 5월 약 2,800명으로 매달 늘어, 6개월 동안 약 2.8배가 되었습니다.
			""";
	private static final String FAKE_PDF_BODY = FAKE_SUMMARY + "\n";
	private static final String UNREADABLE_ERROR_LINE = "{\"type\":\"error\",\"code\":\"UNREADABLE_IMAGE\","
			+ "\"message\":\"사진을 읽지 못했어요. 다시 촬영해 주세요.\",\"status\":\"UNREADABLE\"}";
	private static final String AI_FAILED_ERROR_LINE = "{\"type\":\"error\",\"code\":\"AI_FAILED\","
			+ "\"message\":\"해설을 만들지 못했어요. 잠시 후 다시 시도해 주세요.\",\"status\":\"FAILED\"}";

	@DynamicPropertySource
	static void storageDir(DynamicPropertyRegistry registry) {
		registry.add("file.storage-dir", STORAGE_DIR::toString);
	}

	@Autowired MockMvc mockMvc;
	@Autowired JwtTokenProvider jwtTokenProvider;
	@Autowired JsonMapper jsonMapper;
	@Autowired PromptLoader promptLoader;
	@MockitoSpyBean AnalysisService analysisService;
	@Autowired AnalysisStreamService analysisStreamService;
	@Autowired AnalysisRepository analysisRepository;
	@Autowired MaterialRepository materialRepository;
	@Autowired UserRepository userRepository;
	@Autowired UserSettingsRepository userSettingsRepository;
	@Autowired JdbcTemplate jdbcTemplate;
	@Autowired TransactionTemplate transactionTemplate;

	@MockitoSpyBean LlmClient llmClient;
	@MockitoSpyBean FileStorage fileStorage;

	private final List<Long> userIds = new ArrayList<>();
	private Long userId;
	private Long materialId;

	@BeforeEach
	void setUp() throws IOException {
		Path stored = STORAGE_DIR.resolve(STORED_KEY);
		if (Files.notExists(stored)) {
			Files.write(stored, PNG_BYTES);
		}
		Path storedPdf = STORAGE_DIR.resolve(STORED_PDF_KEY);
		if (Files.notExists(storedPdf)) {
			Files.write(storedPdf, PDF_BYTES);
		}
		userId = saveUser(Difficulty.HARD);
		materialId = saveMaterial(userId);
	}

	@AfterEach
	void cleanUp() {
		for (Long id : userIds) {
			jdbcTemplate.update("delete a from analysis a join material m on a.material_id = m.id where m.user_id = ?", id);
			jdbcTemplate.update("delete from material where user_id = ?", id);
			jdbcTemplate.update("delete from user_settings where user_id = ?", id);
			jdbcTemplate.update("delete from users where id = ?", id);
		}
	}

	@Test
	void streamsEventsInOrderAndSavesCompletedAnalysis() throws Exception {
		MvcResult result = startStream(materialId);

		mockMvc.perform(asyncDispatch(result))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "application/x-ndjson;charset=UTF-8"));

		List<String> lines = lines(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
		Analysis analysis = analysisRepository.findFirstByMaterialIdOrderByIdDesc(materialId).orElseThrow();
		Long analysisId = analysis.getId();

		// 줄 단위 문자열 비교로 키 순서와 명세에 없는 필드가 섞이지 않았는지도 확인한다
		assertThat(lines.get(0)).isEqualTo("{\"type\":\"started\",\"analysisId\":" + analysisId + ",\"difficulty\":\"HARD\"}");
		assertThat(lines.get(1)).isEqualTo("{\"type\":\"progress\",\"stage\":\"ANALYZING\"}");
		assertThat(lines.get(2)).isEqualTo("{\"type\":\"meta\",\"visualType\":\"LINE\",\"title\":\"" + FAKE_TITLE + "\"}");
		assertThat(lines.get(3)).isEqualTo("{\"type\":\"progress\",\"stage\":\"GENERATING\"}");
		assertThat(lines.getLast()).isEqualTo("{\"type\":\"done\",\"analysisId\":" + analysisId + ",\"status\":\"COMPLETED\"}");

		List<String> deltaLines = lines.subList(4, lines.size() - 1);
		assertThat(deltaLines).hasSizeGreaterThan(1);
		StringBuilder summary = new StringBuilder();
		StringBuilder detail = new StringBuilder();
		for (String line : deltaLines) {
			JsonNode node = jsonMapper.readTree(line);
			assertThat(node.propertyNames()).containsExactly("type", "section", "text");
			assertThat(node.get("type").stringValue()).isEqualTo("delta");
			if (node.get("section").stringValue().equals("SUMMARY")) {
				assertThat(detail).as("요약 delta 가 모두 나간 뒤 상세 delta 가 나가야 한다").isEmpty();
				summary.append(node.get("text").stringValue());
			} else {
				assertThat(node.get("section").stringValue()).isEqualTo("DETAIL");
				detail.append(node.get("text").stringValue());
			}
		}
		assertThat(summary.toString()).isEqualTo(FAKE_SUMMARY);
		assertThat(detail.toString()).isEqualTo(FAKE_DETAIL);

		// 전송한 요약·상세와 저장한 값이 같아야 한다
		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(analysis.getSummary()).isEqualTo(summary.toString());
		assertThat(analysis.getDetail()).isEqualTo(detail.toString());
		assertThat(analysis.getVisualType()).isEqualTo(VisualType.LINE);
		assertThat(analysis.getModel()).isEqualTo("fake");
		assertThat(analysis.getPromptVersion()).isEqualTo(promptLoader.get(SourceType.IMAGE, Difficulty.HARD).version());
		assertThat(analysis.getDifficulty()).isEqualTo(Difficulty.HARD);
		assertThat(materialRepository.findById(materialId).orElseThrow().getTitle()).isEqualTo(FAKE_TITLE);
	}

	@Test
	void pdfStreamsTitleOnlyMetaAndSavesNullVisualType() throws Exception {
		Long pdfMaterialId = savePdfMaterial(userId);
		MvcResult result = startStream(pdfMaterialId);

		mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

		List<String> lines = lines(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
		Analysis analysis = analysisRepository.findFirstByMaterialIdOrderByIdDesc(pdfMaterialId).orElseThrow();
		Long analysisId = analysis.getId();

		assertThat(lines.get(0)).isEqualTo("{\"type\":\"started\",\"analysisId\":" + analysisId + ",\"difficulty\":\"HARD\"}");
		assertThat(lines.get(1)).isEqualTo("{\"type\":\"progress\",\"stage\":\"ANALYZING\"}");
		// PDF 는 visualType 키 자체가 없어야 한다
		assertThat(lines.get(2)).isEqualTo("{\"type\":\"meta\",\"title\":\"" + FAKE_PDF_TITLE + "\"}");
		assertThat(lines.get(3)).isEqualTo("{\"type\":\"progress\",\"stage\":\"GENERATING\"}");
		assertThat(lines.getLast()).isEqualTo("{\"type\":\"done\",\"analysisId\":" + analysisId + ",\"status\":\"COMPLETED\"}");

		List<String> deltaLines = lines.subList(4, lines.size() - 1);
		assertThat(deltaLines).isNotEmpty();
		StringBuilder body = new StringBuilder();
		for (String line : deltaLines) {
			assertThat(line).startsWith("{\"type\":\"delta\",\"section\":\"SUMMARY\",\"text\":");
			body.append(jsonMapper.readTree(line).get("text").stringValue());
		}
		assertThat(body.toString()).isEqualTo(FAKE_PDF_BODY);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(analysis.getVisualType()).isNull();
		assertThat(jdbcTemplate.queryForObject("select visual_type from analysis where id = ?", String.class, analysisId))
				.isNull();
		assertThat(analysis.getSummary()).isEqualTo(FAKE_PDF_BODY);
		// 상세 구간이 없으면 detail 은 NULL 로 저장한다
		assertThat(analysis.getDetail()).isNull();
		assertThat(jdbcTemplate.queryForObject("select detail from analysis where id = ?", String.class, analysisId))
				.isNull();
		assertThat(analysis.getPromptVersion()).isEqualTo(promptLoader.get(SourceType.PDF, Difficulty.HARD).version());
		assertThat(materialRepository.findById(pdfMaterialId).orElseThrow().getTitle()).isEqualTo(FAKE_PDF_TITLE);
	}

	@Test
	void pdfMaterialIsSentToLlmAsPdfFile() throws Exception {
		Long pdfMaterialId = savePdfMaterial(userId);
		AtomicReference<LlmRequest> sent = new AtomicReference<>();
		doAnswer(invocation -> {
			sent.set(invocation.getArgument(0));
			return invocation.callRealMethod();
		}).when(llmClient).stream(any());

		mockMvc.perform(asyncDispatch(startStream(pdfMaterialId))).andExpect(status().isOk());

		LlmInput.File file = (LlmInput.File)sent.get().input();
		assertThat(file.mimeType()).isEqualTo("application/pdf");
		assertThat(file.bytes()).isEqualTo(PDF_BYTES);
		assertThat(sent.get().prompt()).isEqualTo(promptLoader.get(SourceType.PDF, Difficulty.HARD).content());
	}

	@Test
	void completedMaterialReturnsAlreadyCompleted() throws Exception {
		Long analysisId = saveAnalysis(materialId);
		analysisService.complete(analysisId, materialId, VisualType.LINE, "제목", "본문", null, "fake", "v1");

		expectError(materialId, ResultCode.ANALYSIS_ALREADY_COMPLETED);
		assertThat(analysisCount(materialId)).isEqualTo(1);
	}

	@Test
	void pendingWithinTimeoutReturnsInProgress() throws Exception {
		saveAnalysis(materialId);

		expectError(materialId, ResultCode.ANALYSIS_IN_PROGRESS);
		assertThat(analysisCount(materialId)).isEqualTo(1);
	}

	@Test
	void pendingPastTimeoutCanBeRestarted() throws Exception {
		Long staleId = saveAnalysis(materialId);
		jdbcTemplate.update("update analysis set created_at = ? where id = ?", LocalDateTime.now().minusSeconds(61), staleId);

		mockMvc.perform(asyncDispatch(startStream(materialId))).andExpect(status().isOk());

		Analysis latest = analysisRepository.findFirstByMaterialIdOrderByIdDesc(materialId).orElseThrow();
		assertThat(latest.getId()).isNotEqualTo(staleId);
		assertThat(latest.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
	}

	@Test
	void otherUsersDeletedAndMissingMaterialsReturnNotFound() throws Exception {
		Long otherUsersMaterialId = saveMaterial(saveUser(Difficulty.NORMAL));
		Long deletedMaterialId = saveMaterial(userId);
		transactionTemplate.executeWithoutResult(
				status -> materialRepository.findById(deletedMaterialId).orElseThrow().delete());

		expectError(otherUsersMaterialId, ResultCode.RESOURCE_NOT_FOUND);
		expectError(deletedMaterialId, ResultCode.RESOURCE_NOT_FOUND);
		expectError(-1L, ResultCode.RESOURCE_NOT_FOUND);
		assertThat(analysisCount(otherUsersMaterialId)).isZero();
		assertThat(analysisCount(deletedMaterialId)).isZero();
	}

	@Test
	void requestWithoutTokenReturnsUnauthorized() throws Exception {
		mockMvc.perform(post(path(materialId)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.resultCode").value("UNAUTHORIZED"));
		assertThat(analysisCount(materialId)).isZero();
	}

	@Test
	void concurrentStartsCreateOnlyOneAnalysis() throws Exception {
		int requests = 2;
		CountDownLatch ready = new CountDownLatch(requests);
		CountDownLatch go = new CountDownLatch(1);
		Callable<Object> startTask = () -> {
			ready.countDown();
			go.await();
			try {
				return analysisService.start(userId, materialId);
			} catch (BusinessException e) {
				return e.getResultCode();
			}
		};

		List<Object> outcomes = new ArrayList<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(requests)) {
			List<Future<Object>> futures = new ArrayList<>();
			for (int i = 0; i < requests; i++) {
				futures.add(executor.submit(startTask));
			}
			ready.await();
			go.countDown();
			for (Future<Object> future : futures) {
				outcomes.add(future.get());
			}
		}

		assertThat(outcomes).filteredOn(AnalysisService.AnalysisStart.class::isInstance).hasSize(1);
		assertThat(outcomes).filteredOn(ResultCode.ANALYSIS_IN_PROGRESS::equals).hasSize(1);
		assertThat(analysisCount(materialId)).isEqualTo(1);
	}

	@Test
	void llmFailureMidStreamSavesFailedAndAllowsRetry() throws Exception {
		doReturn(failingStream()).when(llmClient).stream(any());

		MvcResult failed = startStream(materialId);
		mockMvc.perform(asyncDispatch(failed));

		List<String> lines = lines(failed.getResponse().getContentAsString(StandardCharsets.UTF_8));
		assertThat(lines).noneMatch(line -> line.contains("\"done\""));
		// HTTP 상태는 200 이고, error 가 마지막 줄이다
		assertThat(failed.getResponse().getStatus()).isEqualTo(200);
		assertThat(lines.getLast()).isEqualTo(AI_FAILED_ERROR_LINE);
		assertThat(lines).filteredOn(line -> line.contains("\"error\"")).hasSize(1);
		Analysis failedAnalysis = analysisRepository.findFirstByMaterialIdOrderByIdDesc(materialId).orElseThrow();
		assertThat(failedAnalysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(materialRepository.findById(materialId).orElseThrow().getTitle()).isNull();

		doAnswer(invocation -> invocation.callRealMethod()).when(llmClient).stream(any());
		mockMvc.perform(asyncDispatch(startStream(materialId))).andExpect(status().isOk());

		assertThat(analysisRepository.findFirstByMaterialIdOrderByIdDesc(materialId).orElseThrow().getStatus())
				.isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(analysisCount(materialId)).isEqualTo(2);
	}

	@Test
	void clientDisconnectSavesFailedClosesLlmStreamAndAllowsRetry() throws Exception {
		AtomicReference<LlmStream> opened = new AtomicReference<>();
		doAnswer(invocation -> {
			LlmStream stream = (LlmStream)invocation.callRealMethod();
			opened.set(stream);
			return stream;
		}).when(llmClient).stream(any());

		StreamingResponseBody body = analysisStreamService.stream(userId, materialId);
		// meta 까지 보낸 뒤 첫 delta 에서 연결이 끊긴 상황
		DisconnectingOutputStream out = new DisconnectingOutputStream(4);
		body.writeTo(out);

		// 끊긴 연결에는 error 를 보내려 하지 않는다 (끊기기 전까지 쓴 줄에도 error·done 이 없다)
		assertThat(out.buffer.toString(StandardCharsets.UTF_8)).doesNotContain("\"error\"").doesNotContain("\"done\"");

		assertThat(opened.get()).isNotNull();
		assertThat(opened.get().isClosed()).isTrue();
		assertThat(analysisRepository.findFirstByMaterialIdOrderByIdDesc(materialId).orElseThrow().getStatus())
				.isEqualTo(AnalysisStatus.FAILED);

		// 끊긴 스트림이 실행 자리를 반환했어야 다시 시작할 수 있다
		analysisStreamService.stream(userId, materialId).writeTo(new ByteArrayOutputStream());
		assertThat(analysisCount(materialId)).isEqualTo(2);
	}

	@Test
	void requestOverLimitReturnsAiBusyWithoutCreatingAnalysis() throws Exception {
		Long otherMaterialId = saveMaterial(userId);
		StreamingResponseBody held = analysisStreamService.stream(userId, materialId);
		try {
			expectError(otherMaterialId, ResultCode.AI_BUSY);
			assertThat(analysisCount(otherMaterialId)).isZero();
		} finally {
			held.writeTo(new ByteArrayOutputStream());
		}

		mockMvc.perform(asyncDispatch(startStream(otherMaterialId))).andExpect(status().isOk());
		assertThat(analysisRepository.findFirstByMaterialIdOrderByIdDesc(otherMaterialId).orElseThrow().getStatus())
				.isEqualTo(AnalysisStatus.COMPLETED);
	}

	@Test
	void permitIsReleasedEvenWhenLlmStreamCloseThrows() throws Exception {
		doReturn(closeFailingStream()).when(llmClient).stream(any());

		// 응답이 이미 커밋됐으므로 정리 중 예외도 밖으로 내보내지 않는다 (전역 JSON 예외 처리로 넘어가지 않음)
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertThatCode(() -> analysisStreamService.stream(userId, materialId).writeTo(out)).doesNotThrowAnyException();
		assertThat(lines(out.toString(StandardCharsets.UTF_8)).getLast()).isEqualTo(AI_FAILED_ERROR_LINE);

		doAnswer(invocation -> invocation.callRealMethod()).when(llmClient).stream(any());
		Long otherMaterialId = saveMaterial(userId);
		mockMvc.perform(asyncDispatch(startStream(otherMaterialId))).andExpect(status().isOk());
	}

	@Test
	void startErrorReleasesPermit() throws Exception {
		Long completedMaterialId = saveMaterial(userId);
		Long analysisId = saveAnalysis(completedMaterialId);
		analysisService.complete(analysisId, completedMaterialId, VisualType.LINE, "제목", "본문", null, "fake", "v1");

		expectError(completedMaterialId, ResultCode.ANALYSIS_ALREADY_COMPLETED);

		mockMvc.perform(asyncDispatch(startStream(materialId))).andExpect(status().isOk());
	}

	@Test
	void unreadableImageSendsErrorSavesUnreadableAndAllowsRetry() throws Exception {
		StubLlmStream unreadable = new StubLlmStream(List.of("{\"unreadable\":true}\n", "이후 텍스트는 쓰지 않는다"), null);
		doReturn(unreadable).when(llmClient).stream(any());

		MvcResult result = startStream(materialId);
		mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

		List<String> lines = lines(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
		Long analysisId = latestAnalysis(materialId).getId();
		assertThat(lines).containsExactly(
				"{\"type\":\"started\",\"analysisId\":" + analysisId + ",\"difficulty\":\"HARD\"}",
				"{\"type\":\"progress\",\"stage\":\"ANALYZING\"}",
				UNREADABLE_ERROR_LINE);
		assertThat(latestAnalysis(materialId).getStatus()).isEqualTo(AnalysisStatus.UNREADABLE);
		// 판독 불가가 나오면 남은 출력을 받지 않고 LLM 스트림을 닫는다
		assertThat(unreadable.isClosed()).isTrue();
		assertThat(unreadable.fetched).isEqualTo(1);

		expectRetryCompletes(materialId, 2);
	}

	@Test
	void malformedModelOutputSendsAiFailed() throws Exception {
		doReturn(new StubLlmStream(List.of("메타 JSON 이 아닌 첫 줄\n", "본문"), null)).when(llmClient).stream(any());

		List<String> lines = streamLines(materialId);

		assertThat(lines).hasSize(3);
		assertThat(lines.getLast()).isEqualTo(AI_FAILED_ERROR_LINE);
		assertThat(latestAnalysis(materialId).getStatus()).isEqualTo(AnalysisStatus.FAILED);
		expectRetryCompletes(materialId, 2);
	}

	@Test
	void outputEndingWithoutMetaSendsAiFailed() throws Exception {
		doReturn(new StubLlmStream(List.of("\n", "  \n"), null)).when(llmClient).stream(any());

		List<String> lines = streamLines(materialId);

		assertThat(lines.getLast()).isEqualTo(AI_FAILED_ERROR_LINE);
		assertThat(latestAnalysis(materialId).getStatus()).isEqualTo(AnalysisStatus.FAILED);
	}

	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void unreadableWithoutNewlineIsNotReportedAsMissingMeta(CapturedOutput output) throws Exception {
		// 실제 Gemini 에서 메타 없이 끝났다며 AI_FAILED 가 난 사례 재현: 판독 불가 메타 줄이 줄바꿈 없이 끝나
		// 판독 불가 이벤트가 parser.finish 에서야 나온다
		doReturn(new StubLlmStream(List.of("{\"unreadable\":", "true}"), null)).when(llmClient).stream(any());

		List<String> lines = streamLines(materialId);

		assertThat(lines.getLast()).isEqualTo(UNREADABLE_ERROR_LINE);
		assertThat(latestAnalysis(materialId).getStatus()).isEqualTo(AnalysisStatus.UNREADABLE);
		assertThat(output.getOut()).contains("이미지를 판독할 수 없다는 응답입니다")
				.doesNotContain("메타 없이 해설 스트림이 끝났습니다");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("unreadableOutputs")
	void unreadableOutputSendsUnreadableImageAndSavesUnreadable(String description, List<String> chunks)
			throws Exception {
		StubLlmStream unreadable = new StubLlmStream(chunks, null);
		doReturn(unreadable).when(llmClient).stream(any());

		List<String> lines = streamLines(materialId);

		Long analysisId = latestAnalysis(materialId).getId();
		assertThat(lines).containsExactly(
				"{\"type\":\"started\",\"analysisId\":" + analysisId + ",\"difficulty\":\"HARD\"}",
				"{\"type\":\"progress\",\"stage\":\"ANALYZING\"}",
				UNREADABLE_ERROR_LINE);
		assertThat(latestAnalysis(materialId).getStatus()).isEqualTo(AnalysisStatus.UNREADABLE);
		assertThat(unreadable.isClosed()).isTrue();
	}

	static Stream<Arguments> unreadableOutputs() {
		return Stream.of(
				Arguments.of("줄바꿈 없음", List.of("{\"unreadable\":true}")),
				Arguments.of("줄바꿈 있음", List.of("{\"unreadable\":true}\n")),
				Arguments.of("여러 조각, 줄바꿈 없음", List.of("{\"unrea", "dable\":", " tr", "ue}")));
	}

	@Test
	void lastSummaryReleasedOnlyByFinishIsSentAndSaved() throws Exception {
		// 이미지 요약은 문장 단위로 나가므로, 끝맺음 없는 마지막 문장은 parser.finish 에서야 delta 로 나온다
		doReturn(new StubLlmStream(List.of("{\"visualType\":\"BAR\",\"title\":\"막대\"}\n", "첫 문장입니다. 마지막 문장"), null))
				.when(llmClient).stream(any());

		List<String> lines = streamLines(materialId);

		StringBuilder sent = new StringBuilder();
		for (String line : lines) {
			JsonNode node = jsonMapper.readTree(line);
			if (node.get("type").stringValue().equals("delta")) {
				sent.append(node.get("text").stringValue());
			}
		}
		assertThat(sent.toString()).isEqualTo("첫 문장입니다. 마지막 문장");
		assertThat(lines.getLast()).startsWith("{\"type\":\"done\"");
		assertThat(latestAnalysis(materialId).getSummary()).isEqualTo("첫 문장입니다. 마지막 문장");
	}

	@Test
	void metaLineWithoutNewlineIsHandledByFinish() throws Exception {
		// 메타 줄이 줄바꿈 없이 끝나 메타 이벤트가 parser.finish 에서야 나와도 meta 를 보내고 판정한다.
		// 본문이 없으므로 요약 없음으로 AI_FAILED 가 된다
		doReturn(new StubLlmStream(List.of("{\"visualType\":\"BAR\",", "\"title\":\"막대\"}"), null))
				.when(llmClient).stream(any());

		List<String> lines = streamLines(materialId);

		assertThat(lines.get(2)).isEqualTo("{\"type\":\"meta\",\"visualType\":\"BAR\",\"title\":\"막대\"}");
		assertThat(lines.get(3)).isEqualTo("{\"type\":\"progress\",\"stage\":\"GENERATING\"}");
		assertThat(lines.getLast()).isEqualTo(AI_FAILED_ERROR_LINE);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("outputsWithoutSummary")
	void outputWithoutSummarySendsAiFailedAndAllowsRetry(String description, SourceType sourceType, List<String> chunks)
			throws Exception {
		Long targetId = sourceType == SourceType.PDF ? savePdfMaterial(userId) : materialId;
		doReturn(new StubLlmStream(chunks, null)).when(llmClient).stream(any());

		List<String> lines = streamLines(targetId);

		assertThat(lines).noneMatch(line -> line.contains("\"done\""));
		assertThat(lines.getLast()).isEqualTo(AI_FAILED_ERROR_LINE);
		Analysis failed = latestAnalysis(targetId);
		assertThat(failed.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(failed.getSummary()).isNull();
		assertThat(failed.getDetail()).isNull();
		assertThat(materialRepository.findById(targetId).orElseThrow().getTitle()).isNull();

		expectRetryCompletes(targetId, 2);
	}

	static Stream<Arguments> outputsWithoutSummary() {
		String imageMeta = "{\"visualType\":\"BAR\",\"title\":\"막대\"}\n";
		String pdfMeta = "{\"title\":\"보고서\"}\n";
		return Stream.of(
				Arguments.of("이미지 메타만", SourceType.IMAGE, List.of(imageMeta)),
				Arguments.of("이미지 공백뿐인 요약", SourceType.IMAGE, List.of(imageMeta, "  \t ", " \n  ")),
				Arguments.of("이미지 상세만", SourceType.IMAGE, List.of(imageMeta, "@@DETAIL@@\n", "상세 해설만 있습니다.\n")),
				Arguments.of("PDF 메타만", SourceType.PDF, List.of(pdfMeta)),
				Arguments.of("PDF 공백뿐인 요약", SourceType.PDF, List.of(pdfMeta, "   ", "\t \n ")),
				Arguments.of("PDF 상세만", SourceType.PDF, List.of(pdfMeta, "@@DETAIL@@\n상세 해설만 있습니다.\n")));
	}

	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void malformedOutputWarnLogsLengthWithoutModelOutput(CapturedOutput output) throws Exception {
		stubMalformedOutput();

		List<String> lines = streamLines(materialId);

		assertThat(lines.getLast()).isEqualTo(AI_FAILED_ERROR_LINE);
		// 모델 출력에는 사용자 자료 내용이 담길 수 있으므로 WARN 에는 길이와 종료 정보만 남긴다
		assertThat(output.getOut()).contains("모델 출력 형식이 잘못되었습니다")
				.contains("receivedLength=" + (12 + 120) + ", finishInfo=null")
				.doesNotContain("햄버거 사진입니다")
				.doesNotContain("head=");
	}

	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void modelOutputHeadIsLoggedOnlyAtDebug(CapturedOutput output) throws Exception {
		stubMalformedOutput();
		Logger logger = (Logger)LoggerFactory.getLogger(AnalysisStreamService.class);
		Level previous = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		try {
			streamLines(materialId);
		} finally {
			logger.setLevel(previous);
		}

		// 앞부분은 100자까지만, 줄바꿈은 \n·\r 로 표시한다
		String head = "햄버거 사진입니다.\\r\\n" + "가".repeat(100 - "햄버거 사진입니다.\r\n".length());
		assertThat(output.getOut()).contains("DEBUG").contains("해설 스트림 모델 출력 앞부분")
				.contains("head=\"" + head + "\"")
				.contains("모델 출력 형식 오류 상세").contains("메타 줄이 JSON 이 아닙니다: 햄버거 사진입니다.");
	}

	@Test
	void fileReadFailureSendsAiFailedInsteadOfDisconnectAndAllowsRetry() throws Exception {
		doReturn(new AbstractResource() {
			@Override
			public String getDescription() {
				return "읽을 수 없는 파일";
			}

			@Override
			public InputStream getInputStream() throws IOException {
				throw new IOException("디스크 읽기 오류");
			}
		}).when(fileStorage).load(any());

		List<String> lines = streamLines(materialId);

		// 출력 연결은 살아 있으므로 연결 끊김처럼 조용히 끝내지 않고 error 를 보낸다
		Long analysisId = latestAnalysis(materialId).getId();
		assertThat(lines).containsExactly(
				"{\"type\":\"started\",\"analysisId\":" + analysisId + ",\"difficulty\":\"HARD\"}",
				"{\"type\":\"progress\",\"stage\":\"ANALYZING\"}",
				AI_FAILED_ERROR_LINE);
		assertThat(latestAnalysis(materialId).getStatus()).isEqualTo(AnalysisStatus.FAILED);
		verify(llmClient, never()).stream(any());

		doAnswer(invocation -> invocation.callRealMethod()).when(fileStorage).load(any());
		expectRetryCompletes(materialId, 2);
	}

	@Test
	void failedStatusSaveFailureEndsWithoutError() throws Exception {
		doReturn(new StubLlmStream(List.of(), new IllegalStateException("LLM 호출 실패"))).when(llmClient).stream(any());
		doThrow(new IllegalStateException("DB 오류")).when(analysisService).fail(anyLong());

		List<String> lines = streamLines(materialId);

		// 저장하지 못한 상태(FAILED)를 error 로 알리지 않고, done·error 없이 끝낸다
		assertThat(lines).hasSize(2);
		assertThat(lines.getLast()).isEqualTo("{\"type\":\"progress\",\"stage\":\"ANALYZING\"}");
		assertThat(lines).noneMatch(line -> line.contains("\"error\"") || line.contains("\"done\""));
		// 상태가 PENDING 으로 남아 제한 시간 전에는 다시 시작할 수 없다 (error 를 보내면 안 되는 이유)
		assertThat(latestAnalysis(materialId).getStatus()).isEqualTo(AnalysisStatus.PENDING);
		expectError(materialId, ResultCode.ANALYSIS_IN_PROGRESS);
	}

	@Test
	void unreadableStatusSaveFailureDoesNotClaimUnreadable() throws Exception {
		doReturn(new StubLlmStream(List.of("{\"unreadable\":true}\n"), null)).when(llmClient).stream(any());
		doThrow(new IllegalStateException("DB 오류")).when(analysisService).markUnreadable(anyLong());

		List<String> lines = streamLines(materialId);

		assertThat(lines).hasSize(2);
		assertThat(lines).noneMatch(line -> line.contains("\"error\""));
		// 정리 단계의 FAILED 저장은 성공하므로 같은 자료로 다시 요청할 수 있다
		assertThat(latestAnalysis(materialId).getStatus()).isEqualTo(AnalysisStatus.FAILED);
		expectRetryCompletes(materialId, 2);
	}

	private void stubMalformedOutput() {
		// 첫 줄은 두 번째 조각의 줄바꿈에서 끝나므로, 형식 오류가 나기 전에 두 조각을 모두 받는다
		doReturn(new StubLlmStream(List.of("햄버거 사진입니다.\r", "\n" + "가".repeat(120)), null))
				.when(llmClient).stream(any());
	}

	@Test
	void llmCallFailureBeforeAnyChunkSendsAiFailed() throws Exception {
		StubLlmStream failing = new StubLlmStream(List.of(), new IllegalStateException("429 RESOURCE_EXHAUSTED"));
		doReturn(failing).when(llmClient).stream(any());

		List<String> lines = streamLines(materialId);

		assertThat(lines).hasSize(3);
		assertThat(lines.getLast()).isEqualTo(AI_FAILED_ERROR_LINE);
		assertThat(latestAnalysis(materialId).getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(failing.isClosed()).isTrue();
		expectRetryCompletes(materialId, 2);
	}

	@Test
	void savesModelThatActuallyResponded() throws Exception {
		StubLlmStream fallback = new StubLlmStream(List.of("{\"visualType\":\"BAR\",\"title\":\"막대\"}\n", "본문"), null);
		fallback.model = "gemini-3.5-flash-lite";
		doReturn(fallback).when(llmClient).stream(any());

		List<String> lines = streamLines(materialId);

		assertThat(lines.getLast()).startsWith("{\"type\":\"done\"");
		assertThat(latestAnalysis(materialId).getModel()).isEqualTo("gemini-3.5-flash-lite");
	}

	@Test
	void supersededAnalysisSkipsSavingAndDone() throws Exception {
		AtomicReference<Long> newerId = new AtomicReference<>();
		doAnswer(invocation -> {
			// 이 스트림이 해설을 만드는 동안 같은 자료에 더 새 요청이 시작된 상황 (제한 시간이 지난 PENDING 을 대체)
			newerId.set(saveAnalysis(materialId));
			return invocation.callRealMethod();
		}).when(llmClient).stream(any());

		MvcResult result = startStream(materialId);
		mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

		List<String> lines = lines(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
		assertThat(lines).noneMatch(line -> line.contains("\"done\""));
		assertThat(lines.getLast()).isEqualTo(AI_FAILED_ERROR_LINE);

		Long supersededId = jsonMapper.readTree(lines.getFirst()).get("analysisId").longValue();
		Analysis superseded = analysisRepository.findById(supersededId).orElseThrow();
		assertThat(superseded.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(superseded.getSummary()).isNull();
		assertThat(superseded.getModel()).isNull();
		assertThat(materialRepository.findById(materialId).orElseThrow().getTitle()).isNull();
		assertThat(analysisRepository.findById(newerId.get()).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PENDING);
	}

	@Test
	void completeSkipsSupersededAnalysis() {
		Long olderId = saveAnalysis(materialId);
		Long newerId = saveAnalysis(materialId);

		boolean saved = analysisService.complete(olderId, materialId, VisualType.BAR, "제목", "본문", null, "fake", "v1");

		assertThat(saved).isFalse();
		assertThat(analysisRepository.findById(olderId).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PENDING);
		assertThat(materialRepository.findById(materialId).orElseThrow().getTitle()).isNull();
		assertThat(analysisService.complete(newerId, materialId, VisualType.BAR, "제목", "본문", null, "fake", "v1"))
				.isTrue();
	}

	@Test
	void completeTruncatesTitleTo100Characters() {
		Long analysisId = saveAnalysis(materialId);
		// 이모지(서로게이트 쌍)가 100번째 문자에 걸려도 쪼개지지 않아야 한다
		String title = "가".repeat(99) + "😀" + "나".repeat(10);

		analysisService.complete(analysisId, materialId, VisualType.BAR, title, "본문", null, "fake", "v1");

		String saved = materialRepository.findById(materialId).orElseThrow().getTitle();
		assertThat(saved).isEqualTo("가".repeat(99) + "😀");
		assertThat(saved.codePointCount(0, saved.length())).isEqualTo(100);
	}

	private MvcResult startStream(Long materialId) throws Exception {
		MvcResult result = mockMvc.perform(post(path(materialId)).header("Authorization", bearer()))
				.andExpect(request().asyncStarted())
				.andReturn();
		result.getAsyncResult();
		return result;
	}

	private void expectError(Long materialId, ResultCode resultCode) throws Exception {
		mockMvc.perform(post(path(materialId)).header("Authorization", bearer()))
				.andExpect(status().is(resultCode.getStatus().value()))
				.andExpect(header().string("Content-Type", "application/json"))
				.andExpect(jsonPath("$.resultCode").value(resultCode.name()))
				.andExpect(jsonPath("$.msg").value(resultCode.getMessage()))
				.andExpect(jsonPath("$.data").doesNotExist());
	}

	private List<String> streamLines(Long materialId) throws Exception {
		MvcResult result = startStream(materialId);
		mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
		return lines(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

	/**
	 * 실패한 자료를 같은 자료로 다시 요청하면 새 analysis 로 완료된다.
	 */
	private void expectRetryCompletes(Long materialId, int expectedCount) throws Exception {
		doAnswer(invocation -> invocation.callRealMethod()).when(llmClient).stream(any());
		List<String> lines = streamLines(materialId);
		assertThat(lines.getLast()).startsWith("{\"type\":\"done\"");
		assertThat(latestAnalysis(materialId).getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(analysisCount(materialId)).isEqualTo(expectedCount);
	}

	private Analysis latestAnalysis(Long materialId) {
		return analysisRepository.findFirstByMaterialIdOrderByIdDesc(materialId).orElseThrow();
	}

	private static String path(Long materialId) {
		return "/api/v1/materials/" + materialId + "/analyses";
	}

	private String bearer() {
		return "Bearer " + jwtTokenProvider.createAccessToken(userId);
	}

	private static List<String> lines(String content) {
		assertThat(content).endsWith("\n");
		return List.of(content.split("\n"));
	}

	private Long saveUser(Difficulty difficulty) {
		return transactionTemplate.execute(status -> {
			User user = userRepository.save(User.builder()
					.name("김눈치")
					.email("analysis-test-" + UUID.randomUUID() + "@example.com")
					.passwordHash("hash")
					.build());
			UserSettings settings = new UserSettings(user);
			settings.changeDifficulty(difficulty);
			userSettingsRepository.save(settings);
			userIds.add(user.getId());
			return user.getId();
		});
	}

	private Long saveMaterial(Long ownerId) {
		return materialRepository.save(Material.builder()
				.user(userRepository.getReferenceById(ownerId))
				.sourceType(SourceType.IMAGE)
				.originalFilename("chart.png")
				.filePath(STORED_KEY)
				.mimeType("image/png")
				.fileSize(PNG_BYTES.length)
				.build()).getId();
	}

	private Long savePdfMaterial(Long ownerId) {
		return materialRepository.save(Material.builder()
				.user(userRepository.getReferenceById(ownerId))
				.sourceType(SourceType.PDF)
				.originalFilename("lecture.pdf")
				.filePath(STORED_PDF_KEY)
				.mimeType("application/pdf")
				.fileSize(PDF_BYTES.length)
				.pageCount(1)
				.build()).getId();
	}

	private Long saveAnalysis(Long materialId) {
		return analysisRepository.save(new Analysis(materialRepository.getReferenceById(materialId), Difficulty.NORMAL))
				.getId();
	}

	private long analysisCount(Long materialId) {
		return jdbcTemplate.queryForObject("select count(*) from analysis where material_id = ?", Long.class, materialId);
	}

	/**
	 * 메타 줄을 보낸 뒤 LLM 호출이 실패하는 스트림.
	 */
	private static LlmStream failingStream() {
		return new LlmStream() {
			private boolean closed;

			@Override
			public Iterator<String> iterator() {
				return new Iterator<>() {
					private int index;

					@Override
					public boolean hasNext() {
						if (index >= 2) {
							throw new IllegalStateException("LLM 호출 실패");
						}
						return true;
					}

					@Override
					public String next() {
						return index++ == 0 ? "{\"visualType\":\"BAR\",\"title\":\"막대\"}\n" : "본문 일부";
					}
				};
			}

			@Override
			public boolean isClosed() {
				return closed;
			}

			@Override
			public String model() {
				return "stub";
			}

			@Override
			public void close() {
				closed = true;
			}
		};
	}

	/**
	 * 조각 없이 끝나고, close 에서 예외를 던지는 스트림.
	 */
	private static LlmStream closeFailingStream() {
		return new LlmStream() {
			@Override
			public Iterator<String> iterator() {
				return List.<String>of().iterator();
			}

			@Override
			public boolean isClosed() {
				return false;
			}

			@Override
			public String model() {
				return "stub";
			}

			@Override
			public void close() {
				throw new IllegalStateException("close 실패");
			}
		};
	}

	/**
	 * 정해 둔 조각을 내보낸 뒤, failure 가 있으면 그 예외를 던지는 스트림. close 이후에는 순회가 끝난다.
	 */
	private static final class StubLlmStream implements LlmStream {

		private final List<String> chunks;
		private final RuntimeException failure;
		private volatile boolean closed;
		private int fetched;
		private String model = "stub";

		StubLlmStream(List<String> chunks, RuntimeException failure) {
			this.chunks = chunks;
			this.failure = failure;
		}

		@Override
		public Iterator<String> iterator() {
			return new Iterator<>() {
				@Override
				public boolean hasNext() {
					if (closed) {
						return false;
					}
					if (fetched < chunks.size()) {
						return true;
					}
					if (failure != null) {
						throw failure;
					}
					return false;
				}

				@Override
				public String next() {
					return chunks.get(fetched++);
				}
			};
		}

		@Override
		public boolean isClosed() {
			return closed;
		}

		@Override
		public String model() {
			return model;
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	/**
	 * flush 를 지정한 횟수만큼 받은 뒤부터 연결이 끊긴 것처럼 IOException 을 던진다. 이벤트 1건당 flush 1번이다.
	 */
	private static final class DisconnectingOutputStream extends OutputStream {

		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		private final int allowedFlushes;
		private int flushes;

		DisconnectingOutputStream(int allowedFlushes) {
			this.allowedFlushes = allowedFlushes;
		}

		@Override
		public void write(int b) throws IOException {
			checkConnected();
			buffer.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			checkConnected();
			buffer.write(b, off, len);
		}

		@Override
		public void flush() throws IOException {
			checkConnected();
			flushes++;
		}

		private void checkConnected() throws IOException {
			if (flushes >= allowedFlushes) {
				throw new IOException("Broken pipe");
			}
		}
	}

	private static Path createTempDir() {
		try {
			return Files.createTempDirectory("noonco-analysis-test");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
