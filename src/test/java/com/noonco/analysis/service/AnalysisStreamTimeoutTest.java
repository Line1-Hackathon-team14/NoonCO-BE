package com.noonco.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.noonco.ai.LlmClient;
import com.noonco.ai.LlmStream;
import com.noonco.analysis.entity.AnalysisStatus;
import com.noonco.analysis.repository.AnalysisRepository;
import com.noonco.material.entity.Material;
import com.noonco.material.entity.SourceType;
import com.noonco.material.repository.MaterialRepository;
import com.noonco.user.entity.User;
import com.noonco.user.entity.UserSettings;
import com.noonco.user.repository.UserRepository;
import com.noonco.user.repository.UserSettingsRepository;

/**
 * 분석 제한 시간(llm.gemini.timeout)을 짧게 둔 별도 context 에서 AI_TIMEOUT 처리를 확인한다.
 * 비동기 요청 제한 시간은 그보다 길게 두어, 분석 제한 시간이 먼저 걸려 error 를 보낼 수 있는지 본다.
 */
@SpringBootTest(properties = { "llm.provider=fake", "llm.fake.chunk-delay=1ms", "llm.gemini.timeout=500ms",
		"spring.mvc.async.request-timeout=5s", "analysis.stream.max-concurrent=1",
		"analysis.stream.ping-interval=50ms" })
class AnalysisStreamTimeoutTest {

	private static final Path STORAGE_DIR = createTempDir();
	private static final byte[] PNG_BYTES = {(byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};
	private static final String STORED_KEY = UUID.randomUUID() + ".png";
	private static final String PING_LINE = "{\"type\":\"ping\"}";
	private static final String AI_TIMEOUT_ERROR_LINE = "{\"type\":\"error\",\"code\":\"AI_TIMEOUT\","
			+ "\"message\":\"해설 생성이 오래 걸려 중단했어요. 다시 시도해 주세요.\",\"status\":\"FAILED\"}";

	@DynamicPropertySource
	static void storageDir(DynamicPropertyRegistry registry) {
		registry.add("file.storage-dir", STORAGE_DIR::toString);
	}

	@Autowired AnalysisStreamService analysisStreamService;
	@Autowired AnalysisRepository analysisRepository;
	@Autowired MaterialRepository materialRepository;
	@Autowired UserRepository userRepository;
	@Autowired UserSettingsRepository userSettingsRepository;
	@Autowired JdbcTemplate jdbcTemplate;
	@Autowired TransactionTemplate transactionTemplate;

	@MockitoSpyBean LlmClient llmClient;

	private Long userId;
	private Long materialId;

	@BeforeEach
	void setUp() throws IOException {
		Path stored = STORAGE_DIR.resolve(STORED_KEY);
		if (Files.notExists(stored)) {
			Files.write(stored, PNG_BYTES);
		}
		userId = saveUser();
		materialId = saveMaterial();
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("delete a from analysis a join material m on a.material_id = m.id where m.user_id = ?", userId);
		jdbcTemplate.update("delete from material where user_id = ?", userId);
		jdbcTemplate.update("delete from user_settings where user_id = ?", userId);
		jdbcTemplate.update("delete from users where id = ?", userId);
	}

	@Test
	void timeoutWhileWaitingForLlmSendsAiTimeoutAndNothingAfter() throws Exception {
		HangingLlmStream hanging = new HangingLlmStream(List.of());
		doReturn(hanging).when(llmClient).stream(any());
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		long startedAt = System.nanoTime();
		analysisStreamService.stream(userId, materialId).writeTo(out);
		Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

		// 분석 제한 시간(500ms)에 끊기고, 비동기 요청 제한 시간(5s)보다 훨씬 먼저 끝난다
		assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(500)).isLessThan(Duration.ofSeconds(3));
		List<String> lines = bodyLines(out);
		assertThat(lines).hasSize(3);
		assertThat(lines.get(0)).startsWith("{\"type\":\"started\"");
		assertThat(lines.get(1)).isEqualTo("{\"type\":\"progress\",\"stage\":\"ANALYZING\"}");
		assertThat(lines.get(2)).isEqualTo(AI_TIMEOUT_ERROR_LINE);
		// 대기 중에는 ping 이 갔고, error 가 마지막 줄이다
		assertThat(allLines(out)).contains(PING_LINE);
		assertThat(allLines(out).getLast()).isEqualTo(AI_TIMEOUT_ERROR_LINE);
		assertThat(hanging.isClosed()).isTrue();
		assertThat(latestStatus()).isEqualTo(AnalysisStatus.FAILED);

		// ping 간격의 몇 배를 기다려도 error 뒤에 ping 이 붙지 않는다
		int size = out.size();
		Thread.sleep(200);
		assertThat(out.size()).isEqualTo(size);

		// 같은 자료로 다시 요청할 수 있다
		doAnswer(invocation -> invocation.callRealMethod()).when(llmClient).stream(any());
		ByteArrayOutputStream retry = new ByteArrayOutputStream();
		analysisStreamService.stream(userId, materialId).writeTo(retry);
		assertThat(allLines(retry).getLast()).startsWith("{\"type\":\"done\"");
		assertThat(latestStatus()).isEqualTo(AnalysisStatus.COMPLETED);
	}

	@Test
	void timeoutDuringGenerationSendsAiTimeoutAfterDeltas() throws Exception {
		// 이미지 요약은 문장 단위로 나가므로, 첫 문장이 끝난 뒤 응답이 멈춘 상황을 만든다
		HangingLlmStream hanging = new HangingLlmStream(
				List.of("{\"visualType\":\"BAR\",\"title\":\"막대\"}\n", "첫 문장입니다. 둘째 문장은"));
		doReturn(hanging).when(llmClient).stream(any());
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		analysisStreamService.stream(userId, materialId).writeTo(out);

		List<String> lines = bodyLines(out);
		assertThat(lines).anyMatch(line -> line.startsWith("{\"type\":\"meta\""));
		assertThat(lines).anyMatch(line -> line.startsWith("{\"type\":\"delta\""));
		assertThat(lines).noneMatch(line -> line.contains("\"done\""));
		assertThat(allLines(out).getLast()).isEqualTo(AI_TIMEOUT_ERROR_LINE);
		assertThat(hanging.isClosed()).isTrue();
		assertThat(latestStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(materialRepository.findById(materialId).orElseThrow().getTitle()).isNull();
	}

	@Test
	void asyncRequestTimeoutMustBeLongerThanAnalysisTimeout() {
		Duration analysisTimeout = Duration.ofSeconds(60);

		assertThatCode(() -> AnalysisStreamService.checkTimeouts(analysisTimeout, Duration.ofSeconds(90)))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> AnalysisStreamService.checkTimeouts(analysisTimeout, Duration.ofSeconds(60)))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> AnalysisStreamService.checkTimeouts(analysisTimeout, Duration.ofSeconds(30)))
				.isInstanceOf(IllegalStateException.class);
		// 설정하지 않았거나 제한 없음(0 이하)이면 검사하지 않는다
		assertThatCode(() -> AnalysisStreamService.checkTimeouts(analysisTimeout, null)).doesNotThrowAnyException();
		assertThatCode(() -> AnalysisStreamService.checkTimeouts(analysisTimeout, Duration.ofMillis(-1)))
				.doesNotThrowAnyException();
	}

	private AnalysisStatus latestStatus() {
		return analysisRepository.findFirstByMaterialIdOrderByIdDesc(materialId).orElseThrow().getStatus();
	}

	private static List<String> allLines(ByteArrayOutputStream out) {
		String content = out.toString(StandardCharsets.UTF_8);
		assertThat(content).endsWith("\n");
		return List.of(content.split("\n"));
	}

	private static List<String> bodyLines(ByteArrayOutputStream out) {
		return allLines(out).stream().filter(line -> !line.equals(PING_LINE)).toList();
	}

	private Long saveUser() {
		return transactionTemplate.execute(status -> {
			User user = userRepository.save(User.builder()
					.name("김눈치")
					.email("timeout-test-" + UUID.randomUUID() + "@example.com")
					.passwordHash("hash")
					.build());
			userSettingsRepository.save(new UserSettings(user));
			return user.getId();
		});
	}

	private Long saveMaterial() {
		return materialRepository.save(Material.builder()
				.user(userRepository.getReferenceById(userId))
				.sourceType(SourceType.IMAGE)
				.originalFilename("chart.png")
				.filePath(STORED_KEY)
				.mimeType("image/png")
				.fileSize(PNG_BYTES.length)
				.build()).getId();
	}

	private static Path createTempDir() {
		try {
			return Files.createTempDirectory("noonco-timeout-test");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * 정해 둔 조각을 내보낸 뒤 close 될 때까지 다음 조각을 주지 않는 LLM 스트림. 응답이 멈춘 모델을 흉내 낸다.
	 */
	private static final class HangingLlmStream implements LlmStream {

		private final List<String> first;
		private final CountDownLatch closed = new CountDownLatch(1);

		HangingLlmStream(List<String> first) {
			this.first = first;
		}

		@Override
		public Iterator<String> iterator() {
			Iterator<String> chunks = first.iterator();
			return new Iterator<>() {
				@Override
				public boolean hasNext() {
					if (isClosed()) {
						return false;
					}
					if (chunks.hasNext()) {
						return true;
					}
					try {
						closed.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					return false;
				}

				@Override
				public String next() {
					return chunks.next();
				}
			};
		}

		@Override
		public boolean isClosed() {
			return closed.getCount() == 0;
		}

		@Override
		public String model() {
			return "stub";
		}

		@Override
		public void close() {
			closed.countDown();
		}
	}
}
