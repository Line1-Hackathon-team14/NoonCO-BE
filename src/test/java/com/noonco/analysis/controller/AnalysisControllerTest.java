package com.noonco.analysis.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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
	// FakeLlmClient 응답에서 첫 줄 메타 JSON 과 그 뒤 줄바꿈을 뺀 본문
	private static final String FAKE_TITLE = "월별 방문자 수 추이";
	private static final String FAKE_PDF_TITLE = "월별 방문자 수 보고서";
	private static final String FAKE_BODY = """
			이 자료는 1월부터 6월까지의 월별 방문자 수를 나타낸 꺾은선 그래프입니다.
			가로축은 월, 세로축은 방문자 수이며, 전체적으로 꾸준히 증가하는 흐름을 보입니다.
			가장 낮은 달은 1월로 약 1,200명이고, 가장 높은 달은 6월로 약 3,400명입니다.
			""";

	@DynamicPropertySource
	static void storageDir(DynamicPropertyRegistry registry) {
		registry.add("file.storage-dir", STORAGE_DIR::toString);
	}

	@Autowired MockMvc mockMvc;
	@Autowired JwtTokenProvider jwtTokenProvider;
	@Autowired JsonMapper jsonMapper;
	@Autowired PromptLoader promptLoader;
	@Autowired AnalysisService analysisService;
	@Autowired AnalysisStreamService analysisStreamService;
	@Autowired AnalysisRepository analysisRepository;
	@Autowired MaterialRepository materialRepository;
	@Autowired UserRepository userRepository;
	@Autowired UserSettingsRepository userSettingsRepository;
	@Autowired JdbcTemplate jdbcTemplate;
	@Autowired TransactionTemplate transactionTemplate;

	@MockitoSpyBean LlmClient llmClient;

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
		StringBuilder body = new StringBuilder();
		for (String line : deltaLines) {
			JsonNode node = jsonMapper.readTree(line);
			assertThat(node.propertyNames()).containsExactly("type", "text");
			assertThat(node.get("type").stringValue()).isEqualTo("delta");
			body.append(node.get("text").stringValue());
		}
		assertThat(body.toString()).isEqualTo(FAKE_BODY);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(analysis.getSummary()).isEqualTo(FAKE_BODY);
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
			JsonNode node = jsonMapper.readTree(line);
			assertThat(node.propertyNames()).containsExactly("type", "text");
			assertThat(node.get("type").stringValue()).isEqualTo("delta");
			body.append(node.get("text").stringValue());
		}
		assertThat(body.toString()).isEqualTo(FAKE_BODY);

		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
		assertThat(analysis.getVisualType()).isNull();
		assertThat(jdbcTemplate.queryForObject("select visual_type from analysis where id = ?", String.class, analysisId))
				.isNull();
		assertThat(analysis.getSummary()).isEqualTo(FAKE_BODY);
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
		analysisService.complete(analysisId, materialId, VisualType.LINE, "제목", "본문", "fake", "v1");

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
		body.writeTo(new DisconnectingOutputStream(4));

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

		assertThatThrownBy(() -> analysisStreamService.stream(userId, materialId).writeTo(new ByteArrayOutputStream()))
				.isInstanceOf(IllegalStateException.class);

		doAnswer(invocation -> invocation.callRealMethod()).when(llmClient).stream(any());
		Long otherMaterialId = saveMaterial(userId);
		mockMvc.perform(asyncDispatch(startStream(otherMaterialId))).andExpect(status().isOk());
	}

	@Test
	void startErrorReleasesPermit() throws Exception {
		Long completedMaterialId = saveMaterial(userId);
		Long analysisId = saveAnalysis(completedMaterialId);
		analysisService.complete(analysisId, completedMaterialId, VisualType.LINE, "제목", "본문", "fake", "v1");

		expectError(completedMaterialId, ResultCode.ANALYSIS_ALREADY_COMPLETED);

		mockMvc.perform(asyncDispatch(startStream(materialId))).andExpect(status().isOk());
	}

	@Test
	void completeTruncatesTitleTo100Characters() {
		Long analysisId = saveAnalysis(materialId);
		// 이모지(서로게이트 쌍)가 100번째 문자에 걸려도 쪼개지지 않아야 한다
		String title = "가".repeat(99) + "😀" + "나".repeat(10);

		analysisService.complete(analysisId, materialId, VisualType.BAR, title, "본문", "fake", "v1");

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
			public void close() {
				throw new IllegalStateException("close 실패");
			}
		};
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
