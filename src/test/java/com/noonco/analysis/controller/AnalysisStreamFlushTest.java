package com.noonco.analysis.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.noonco.global.jwt.JwtTokenProvider;
import com.noonco.material.entity.Material;
import com.noonco.material.entity.SourceType;
import com.noonco.material.repository.MaterialRepository;
import com.noonco.user.entity.User;
import com.noonco.user.entity.UserSettings;
import com.noonco.user.repository.UserRepository;
import com.noonco.user.repository.UserSettingsRepository;

/**
 * 실제 Tomcat 에 HTTP 요청을 보내, 해설 스트림 이벤트가 끝날 때 한꺼번에 오지 않고 생성되는 대로 도착하는지 확인한다. (#39)
 * Fake LLM 조각 지연을 크게 잡아(9조각 × 500ms = 4.5초) 버퍼링 여부가 시간차로 분명히 드러나게 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "llm.provider=fake", "llm.fake.chunk-delay=500ms", "llm.gemini.timeout=60s" })
class AnalysisStreamFlushTest {

	private static final long CHUNK_DELAY_MILLIS = 500;
	// 개별 delta 간격 하한. 조각 지연보다 훨씬 낮게 잡아 클라이언트 쪽 지연에 흔들리지 않게 한다
	private static final long MIN_DELTA_GAP_MILLIS = 100;
	private static final Path STORAGE_DIR = createTempDir();
	private static final byte[] PNG_BYTES = {(byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};
	private static final String STORED_KEY = UUID.randomUUID() + ".png";

	@DynamicPropertySource
	static void storageDir(DynamicPropertyRegistry registry) {
		registry.add("file.storage-dir", STORAGE_DIR::toString);
	}

	@LocalServerPort int port;
	@Autowired JwtTokenProvider jwtTokenProvider;
	@Autowired MaterialRepository materialRepository;
	@Autowired UserRepository userRepository;
	@Autowired UserSettingsRepository userSettingsRepository;
	@Autowired JdbcTemplate jdbcTemplate;
	@Autowired TransactionTemplate transactionTemplate;

	private Long userId;
	private Long materialId;

	@BeforeEach
	void setUp() throws IOException {
		Files.write(STORAGE_DIR.resolve(STORED_KEY), PNG_BYTES);
		userId = saveUser();
		materialId = saveMaterial(userId);
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("delete a from analysis a join material m on a.material_id = m.id where m.user_id = ?", userId);
		jdbcTemplate.update("delete from material where user_id = ?", userId);
		jdbcTemplate.update("delete from user_settings where user_id = ?", userId);
		jdbcTemplate.update("delete from users where id = ?", userId);
	}

	@Test
	void eventsArriveAsTheyAreGeneratedNotAllAtTheEnd() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(
						URI.create("http://127.0.0.1:" + port + "/api/v1/materials/" + materialId + "/analyses"))
				.header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(userId))
				.POST(HttpRequest.BodyPublishers.noBody())
				.timeout(Duration.ofSeconds(30))
				.build();

		List<Arrival> arrivals = new ArrayList<>();
		try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
			HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			assertThat(response.statusCode()).isEqualTo(200);
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					arrivals.add(new Arrival(System.nanoTime() / 1_000_000, line));
				}
			}
		}

		long started = arrivalOf(arrivals, "\"started\"").get(0);
		long done = arrivalOf(arrivals, "\"done\"").get(0);
		List<Long> deltas = arrivalOf(arrivals, "\"delta\"");
		assertThat(deltas).hasSizeGreaterThan(2);

		// started 는 LLM 조각을 기다리기 전에 보내므로, Fake LLM 전체 소요(약 4.5초)보다 훨씬 먼저 와야 한다.
		// 한꺼번에 오면 두 시각 차이는 거의 0 이다.
		assertThat(done - started).as("started → done 도착 간격(ms)")
				.isGreaterThanOrEqualTo(CHUNK_DELAY_MILLIS * 6);
		// delta 는 조각(500ms)마다 하나씩 생기므로 시간차를 두고 도착해야 한다.
		// 클라이언트 스레드가 잠깐 밀리면 몇 줄이 붙어 올 수 있으니, 개별 간격은 낮은 하한으로 과반만 보고
		// 전체 간격은 기대치(조각 지연 × (delta 수 - 1))의 절반 이상인지 본다. 몰아서 오면 둘 다 거의 0 이다.
		List<Long> gaps = new ArrayList<>();
		for (int i = 1; i < deltas.size(); i++) {
			gaps.add(deltas.get(i) - deltas.get(i - 1));
		}
		assertThat(gaps.stream().filter(gap -> gap >= MIN_DELTA_GAP_MILLIS).count())
				.as("%dms 이상 벌어진 delta 간격 수 (전체 간격: %s)", MIN_DELTA_GAP_MILLIS, gaps)
				.isGreaterThan(gaps.size() / 2);
		assertThat(deltas.getLast() - deltas.getFirst()).as("첫 delta → 마지막 delta 도착 간격(ms)")
				.isGreaterThanOrEqualTo((deltas.size() - 1) * CHUNK_DELAY_MILLIS / 2);
	}

	private static List<Long> arrivalOf(List<Arrival> arrivals, String type) {
		List<Long> times = arrivals.stream()
				.filter(arrival -> arrival.line().contains("\"type\":" + type))
				.map(Arrival::millis)
				.toList();
		assertThat(times).as(type + " 이벤트").isNotEmpty();
		return times;
	}

	private Long saveUser() {
		return transactionTemplate.execute(status -> {
			User user = userRepository.save(User.builder()
					.name("김눈치")
					.email("analysis-flush-test-" + UUID.randomUUID() + "@example.com")
					.passwordHash("hash")
					.build());
			userSettingsRepository.save(new UserSettings(user));
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

	private static Path createTempDir() {
		try {
			return Files.createTempDirectory("noonco-analysis-flush-test");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private record Arrival(long millis, String line) {
	}
}
