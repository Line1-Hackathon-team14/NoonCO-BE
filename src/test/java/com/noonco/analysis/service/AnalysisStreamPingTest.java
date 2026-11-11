package com.noonco.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ping 간격을 짧게 둔 별도 context 에서 ping 전송과 연결 끊김 감지를 확인한다.
 * 동시 실행 수를 1로 두어, 스트림이 끝난 뒤 실행 자리가 반환됐는지도 함께 본다.
 */
@SpringBootTest(properties = { "llm.provider=fake", "llm.fake.chunk-delay=100ms", "llm.gemini.timeout=60s",
		"analysis.stream.max-concurrent=1", "analysis.stream.ping-interval=30ms" })
class AnalysisStreamPingTest {

	private static final Path STORAGE_DIR = createTempDir();
	private static final byte[] PNG_BYTES = {(byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};
	private static final String STORED_KEY = UUID.randomUUID() + ".png";
	private static final String PING_LINE = "{\"type\":\"ping\"}";

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
	@Autowired JsonMapper jsonMapper;

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
	void pingsInterleaveAsWholeLinesWithoutBreakingEvents() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		analysisStreamService.stream(userId, materialId).writeTo(out);

		String content = out.toString(StandardCharsets.UTF_8);
		assertThat(content).endsWith("\n");
		List<String> lines = List.of(content.split("\n"));
		List<String> types = new ArrayList<>();
		StringBuilder body = new StringBuilder();
		for (String line : lines) {
			JsonNode node = jsonMapper.readTree(line);
			String type = node.get("type").stringValue();
			if (type.equals("ping")) {
				assertThat(line).isEqualTo(PING_LINE);
				continue;
			}
			if (type.equals("delta")) {
				body.append(node.get("text").stringValue());
				if (types.getLast().equals("delta")) {
					continue;
				}
			}
			types.add(type);
		}

		assertThat(lines).contains(PING_LINE);
		assertThat(types).containsExactly("started", "progress", "meta", "progress", "delta", "done");
		assertThat(lines.getLast()).startsWith("{\"type\":\"done\"");
		assertThat(body.toString()).startsWith("이 자료는").endsWith("약 2.8배가 되었습니다.\n");
		assertThat(latestStatus()).isEqualTo(AnalysisStatus.COMPLETED);
	}

	@Test
	void disconnectWhileWaitingForLlmIsDetectedByPing() throws Exception {
		BlockingLlmStream blocking = new BlockingLlmStream();
		doReturn(blocking).when(llmClient).stream(any());

		// started, progress(ANALYZING) 를 보낸 뒤 연결이 끊긴다. 이후 LLM 응답 대기 중에는 ping 만 쓴다
		CompletableFuture<Void> streaming = CompletableFuture.runAsync(() -> {
			try {
				analysisStreamService.stream(userId, materialId).writeTo(new DisconnectingOutputStream(2));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		streaming.get(5, TimeUnit.SECONDS);

		assertThat(blocking.isClosed()).isTrue();
		assertThat(latestStatus()).isEqualTo(AnalysisStatus.FAILED);

		// 실행 자리가 반환됐어야 다시 시작할 수 있다
		doAnswer(invocation -> invocation.callRealMethod()).when(llmClient).stream(any());
		analysisStreamService.stream(userId, materialId).writeTo(new ByteArrayOutputStream());
		assertThat(latestStatus()).isEqualTo(AnalysisStatus.COMPLETED);
	}

	private AnalysisStatus latestStatus() {
		return analysisRepository.findFirstByMaterialIdOrderByIdDesc(materialId).orElseThrow().getStatus();
	}

	private Long saveUser() {
		return transactionTemplate.execute(status -> {
			User user = userRepository.save(User.builder()
					.name("김눈치")
					.email("ping-test-" + UUID.randomUUID() + "@example.com")
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
			return Files.createTempDirectory("noonco-ping-test");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * close 될 때까지 첫 조각을 주지 않는 LLM 스트림. 분석 대기 단계를 흉내 낸다.
	 */
	private static final class BlockingLlmStream implements LlmStream {

		private final CountDownLatch closed = new CountDownLatch(1);

		@Override
		public Iterator<String> iterator() {
			return new Iterator<>() {
				@Override
				public boolean hasNext() {
					try {
						closed.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					return false;
				}

				@Override
				public String next() {
					throw new NoSuchElementException();
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

	/**
	 * flush 를 지정한 횟수만큼 받은 뒤부터 연결이 끊긴 것처럼 IOException 을 던진다. 이벤트 1건당 flush 1번이다.
	 */
	private static final class DisconnectingOutputStream extends OutputStream {

		private final int allowedFlushes;
		private int flushes;

		DisconnectingOutputStream(int allowedFlushes) {
			this.allowedFlushes = allowedFlushes;
		}

		@Override
		public void write(int b) throws IOException {
			checkConnected();
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			checkConnected();
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
}
