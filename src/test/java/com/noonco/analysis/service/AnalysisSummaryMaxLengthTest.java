package com.noonco.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.noonco.analysis.entity.Analysis;
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
 * 요약 최대 길이(analysis.stream.summary-max-length)를 낮춘 별도 context 에서,
 * 설정값대로 이미지 요약이 문장 경계에서 잘리고 PDF 요약과 상세 해설은 그대로인지 확인한다.
 */
@SpringBootTest(properties = { "llm.provider=fake", "llm.fake.chunk-delay=1ms", "llm.gemini.timeout=60s",
		"analysis.stream.summary-max-length=50" })
class AnalysisSummaryMaxLengthTest {

	private static final Path STORAGE_DIR = createTempDir();
	private static final byte[] PNG_BYTES = {(byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};
	private static final String STORED_KEY = UUID.randomUUID() + ".png";
	private static final byte[] PDF_BYTES = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
	private static final String STORED_PDF_KEY = UUID.randomUUID() + ".pdf";
	// FakeLlmClient 요약의 첫 문장(42자)과 그 뒤 줄바꿈까지가 50자 안에 들어가고, 다음 문장을 붙이면 넘친다
	private static final String CUT_SUMMARY = "이 자료는 1월부터 6월까지의 월별 방문자 수를 나타낸 꺾은선 그래프입니다.\n";
	private static final String FAKE_DETAIL = """
			가로축은 1월부터 6월까지 한 달 간격이고, 세로축은 방문자 수(명)로 0명부터 4,000명까지 1,000명 간격의 눈금이 있습니다.
			최솟값은 1월의 약 1,200명, 최댓값은 6월의 약 3,400명입니다.
			2월 약 1,500명, 3월 약 1,900명, 4월 약 2,300명, 5월 약 2,800명으로 매달 늘어, 6개월 동안 약 2.8배가 되었습니다.
			""";

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

	private Long userId;

	@BeforeEach
	void setUp() throws IOException {
		Files.write(STORAGE_DIR.resolve(STORED_KEY), PNG_BYTES);
		Files.write(STORAGE_DIR.resolve(STORED_PDF_KEY), PDF_BYTES);
		userId = saveUser();
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("delete a from analysis a join material m on a.material_id = m.id where m.user_id = ?", userId);
		jdbcTemplate.update("delete from material where user_id = ?", userId);
		jdbcTemplate.update("delete from user_settings where user_id = ?", userId);
		jdbcTemplate.update("delete from users where id = ?", userId);
	}

	@Test
	void imageSummaryIsCutAtConfiguredLengthAndSentSummaryEqualsSaved() throws IOException {
		Long materialId = saveMaterial(SourceType.IMAGE, STORED_KEY, "image/png", PNG_BYTES.length);

		Streamed streamed = stream(materialId);
		Analysis analysis = analysisRepository.findFirstByMaterialIdOrderByIdDesc(materialId).orElseThrow();

		assertThat(streamed.summary()).isEqualTo(CUT_SUMMARY);
		assertThat(streamed.summary().codePointCount(0, streamed.summary().length())).isLessThanOrEqualTo(50);
		assertThat(streamed.detail()).isEqualTo(FAKE_DETAIL);
		assertThat(analysis.getSummary()).isEqualTo(streamed.summary());
		assertThat(analysis.getDetail()).isEqualTo(streamed.detail());
	}

	@Test
	void pdfSummaryIsNotLimited() throws IOException {
		Long materialId = saveMaterial(SourceType.PDF, STORED_PDF_KEY, "application/pdf", PDF_BYTES.length);

		Streamed streamed = stream(materialId);
		Analysis analysis = analysisRepository.findFirstByMaterialIdOrderByIdDesc(materialId).orElseThrow();

		assertThat(streamed.summary().codePointCount(0, streamed.summary().length())).isGreaterThan(50);
		assertThat(streamed.summary()).startsWith(CUT_SUMMARY).endsWith("3,400명입니다.\n");
		assertThat(streamed.detail()).isEmpty();
		assertThat(analysis.getSummary()).isEqualTo(streamed.summary());
		assertThat(analysis.getDetail()).isNull();
	}

	private record Streamed(String summary, String detail) {
	}

	private Streamed stream(Long materialId) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		analysisStreamService.stream(userId, materialId).writeTo(out);

		StringBuilder summary = new StringBuilder();
		StringBuilder detail = new StringBuilder();
		for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
			JsonNode node = jsonMapper.readTree(line);
			if (!node.get("type").stringValue().equals("delta")) {
				continue;
			}
			String text = node.get("text").stringValue();
			if (node.get("section").stringValue().equals("SUMMARY")) {
				summary.append(text);
			} else {
				detail.append(text);
			}
		}
		return new Streamed(summary.toString(), detail.toString());
	}

	private Long saveUser() {
		return transactionTemplate.execute(status -> {
			User user = userRepository.save(User.builder()
					.name("김눈치")
					.email("summary-limit-test-" + UUID.randomUUID() + "@example.com")
					.passwordHash("hash")
					.build());
			userSettingsRepository.save(new UserSettings(user));
			return user.getId();
		});
	}

	private Long saveMaterial(SourceType sourceType, String filePath, String mimeType, int fileSize) {
		return materialRepository.save(Material.builder()
				.user(userRepository.getReferenceById(userId))
				.sourceType(sourceType)
				.originalFilename(sourceType == SourceType.PDF ? "report.pdf" : "chart.png")
				.filePath(filePath)
				.mimeType(mimeType)
				.fileSize(fileSize)
				.build()).getId();
	}

	private static Path createTempDir() {
		try {
			return Files.createTempDirectory("noonco-summary-limit-test");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
