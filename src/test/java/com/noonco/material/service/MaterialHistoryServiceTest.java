package com.noonco.material.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.noonco.ai.LlmConfig;
import com.noonco.analysis.entity.Analysis;
import com.noonco.analysis.entity.VisualType;
import com.noonco.analysis.repository.AnalysisRepository;
import com.noonco.global.ResultCode;
import com.noonco.global.file.FileStorage;
import com.noonco.global.file.FileValidator;
import com.noonco.material.dto.MaterialCountResponse;
import com.noonco.material.dto.MaterialDetailResponse;
import com.noonco.material.dto.MaterialListRequest;
import com.noonco.material.dto.MaterialPageResponse;
import com.noonco.material.dto.MaterialSummaryResponse;
import com.noonco.material.entity.Material;
import com.noonco.material.entity.SourceType;
import com.noonco.material.repository.MaterialRepository;
import com.noonco.user.entity.Difficulty;
import com.noonco.user.entity.User;
import com.noonco.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

/**
 * 학습 기록 목록·개수·상세·원본 파일 조회와 자료 삭제를 로컬 DB 로 확인한다.
 * 각 테스트는 새 사용자를 만들어 쓰므로 로컬 DB 의 기존 데이터와 섞이지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ MaterialService.class, FileValidator.class, FileStorage.class, LlmConfig.class })
class MaterialHistoryServiceTest {

	private static final Path STORAGE_DIR = createTempDir();
	private static final byte[] PNG_BYTES = {(byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};

	@DynamicPropertySource
	static void storageDir(DynamicPropertyRegistry registry) {
		registry.add("file.storage-dir", STORAGE_DIR::toString);
	}

	@Autowired MaterialService materialService;
	@Autowired MaterialRepository materialRepository;
	@Autowired AnalysisRepository analysisRepository;
	@Autowired UserRepository userRepository;
	@Autowired EntityManager entityManager;

	private Long userId;
	private Long otherUserId;

	@BeforeEach
	void setUp() {
		userId = saveUser().getId();
		otherUserId = saveUser().getId();
	}

	@Test
	void listContainsOnlyMaterialsWithCompletedAnalysisNewestFirst() {
		Long completedImage = completed(upload(userId), VisualType.LINE);
		Long completedPdf = completed(material(userId, SourceType.PDF, 12), null);
		pending(upload(userId));
		failed(upload(userId));
		upload(userId); // 분석 없음
		completed(upload(otherUserId), VisualType.BAR); // 타인 자료
		Long deleted = completed(upload(userId), VisualType.PIE);
		materialRepository.findById(deleted).orElseThrow().delete();
		flushAndClear();

		MaterialPageResponse page = materialService.getMaterials(userId, request(null, null, null));

		assertThat(page.content()).extracting(MaterialSummaryResponse::materialId)
				.containsExactly(completedPdf, completedImage);
		assertThat(page.totalElements()).isEqualTo(2);
		assertThat(page.page()).isZero();
		assertThat(page.size()).isEqualTo(20);
		assertThat(page.hasNext()).isFalse();

		MaterialSummaryResponse pdf = page.content().getFirst();
		assertThat(pdf.sourceType()).isEqualTo(SourceType.PDF);
		assertThat(pdf.pageCount()).isEqualTo(12);
		assertThat(pdf.visualType()).isNull();
		assertThat(pdf.difficulty()).isEqualTo(Difficulty.NORMAL);
		assertThat(pdf.title()).isEqualTo("제목");

		MaterialSummaryResponse image = page.content().get(1);
		assertThat(image.visualType()).isEqualTo(VisualType.LINE);
		assertThat(image.pageCount()).isNull();
	}

	@Test
	void listFiltersBySourceTypeAndPages() {
		Long image1 = completed(upload(userId), VisualType.LINE);
		Long image2 = completed(upload(userId), VisualType.BAR);
		Long pdf = completed(material(userId, SourceType.PDF, 3), null);
		flushAndClear();

		assertThat(materialService.getMaterials(userId, request(SourceType.PDF, null, null)).content())
				.extracting(MaterialSummaryResponse::materialId).containsExactly(pdf);

		MaterialPageResponse first = materialService.getMaterials(userId, request(SourceType.IMAGE, 0, 1));
		assertThat(first.content()).extracting(MaterialSummaryResponse::materialId).containsExactly(image2);
		assertThat(first.totalElements()).isEqualTo(2);
		assertThat(first.hasNext()).isTrue();

		MaterialPageResponse second = materialService.getMaterials(userId, request(SourceType.IMAGE, 1, 1));
		assertThat(second.content()).extracting(MaterialSummaryResponse::materialId).containsExactly(image1);
		assertThat(second.hasNext()).isFalse();
	}

	@Test
	void countsMatchListTotals() {
		completed(upload(userId), VisualType.LINE);
		completed(upload(userId), VisualType.BAR);
		completed(material(userId, SourceType.PDF, 3), null);
		pending(material(userId, SourceType.PDF, 3));
		flushAndClear();

		MaterialCountResponse counts = materialService.getCounts(userId);

		assertThat(counts).isEqualTo(new MaterialCountResponse(3, 2, 1));
		assertThat(counts.total()).isEqualTo(totalOf(null));
		assertThat(counts.image()).isEqualTo(totalOf(SourceType.IMAGE));
		assertThat(counts.pdf()).isEqualTo(totalOf(SourceType.PDF));
	}

	@Test
	void countsAreZeroWithoutCompletedMaterials() {
		pending(upload(userId));
		flushAndClear();

		assertThat(materialService.getCounts(userId)).isEqualTo(new MaterialCountResponse(0, 0, 0));
	}

	@Test
	void detailIncludesCompletedAnalysis() {
		Long materialId = completed(upload(userId), VisualType.LINE, "축·수치 상세 해설");
		flushAndClear();

		MaterialDetailResponse detail = materialService.getMaterial(userId, materialId);

		assertThat(detail.materialId()).isEqualTo(materialId);
		assertThat(detail.title()).isEqualTo("제목");
		assertThat(detail.originalFilename()).isEqualTo("chart.png");
		assertThat(detail.analysis()).isNotNull();
		assertThat(detail.analysis().summary()).isEqualTo("해설 본문");
		assertThat(detail.analysis().detail()).isEqualTo("축·수치 상세 해설");
		assertThat(detail.analysis().visualType()).isEqualTo(VisualType.LINE);
		assertThat(detail.analysis().difficulty()).isEqualTo(Difficulty.NORMAL);
	}

	@Test
	void detailHasNullDetailWhenAnalysisHasNoDetail() {
		Long materialId = completed(upload(userId), VisualType.LINE);
		flushAndClear();

		MaterialDetailResponse.AnalysisResponse analysis = materialService.getMaterial(userId, materialId).analysis();

		assertThat(analysis).isNotNull();
		assertThat(analysis.summary()).isEqualTo("해설 본문");
		assertThat(analysis.detail()).isNull();
	}

	@Test
	void detailHasNullAnalysisWhenNotCompleted() {
		Long materialId = failed(upload(userId));
		flushAndClear();

		assertThat(materialService.getMaterial(userId, materialId).analysis()).isNull();
	}

	@Test
	void detailAndFileHideOtherUsersAndMissingMaterials() {
		Long materialId = upload(userId);
		flushAndClear();

		assertNotFound(() -> materialService.getMaterial(otherUserId, materialId));
		assertNotFound(() -> materialService.getMaterial(userId, -1L));
		assertNotFound(() -> materialService.getFile(otherUserId, materialId));
	}

	@Test
	void fileReturnsStoredBytesAndMimeType() throws IOException {
		Long materialId = upload(userId);
		flushAndClear();

		MaterialService.MaterialFile file = materialService.getFile(userId, materialId);

		assertThat(file.mimeType()).isEqualTo("image/png");
		assertThat(file.resource().getContentAsByteArray()).isEqualTo(PNG_BYTES);
	}

	@Test
	void deleteHidesMaterialFromAllQueries() {
		Long materialId = completed(upload(userId), VisualType.LINE);
		flushAndClear();

		materialService.deleteMaterial(userId, materialId);
		flushAndClear();

		assertNotFound(() -> materialService.getMaterial(userId, materialId));
		assertNotFound(() -> materialService.getFile(userId, materialId));
		assertNotFound(() -> materialService.deleteMaterial(userId, materialId));
		assertThat(materialService.getMaterials(userId, request(null, null, null)).content()).isEmpty();
		assertThat(materialService.getCounts(userId).total()).isZero();
	}

	@Test
	void deleteIsRejectedWhileAnalysisInProgress() {
		Long materialId = pending(upload(userId));
		flushAndClear();

		assertThatThrownBy(() -> materialService.deleteMaterial(userId, materialId))
				.extracting("resultCode").isEqualTo(ResultCode.ANALYSIS_IN_PROGRESS);
	}

	@Test
	void deleteIsAllowedForStalePendingOrFailedAnalysis() {
		Long stale = pending(upload(userId));
		Long failed = failed(upload(userId));
		flushAndClear();
		// 서버가 중간에 내려가 남은 PENDING: 제한 시간(기본 60초)이 지난 것으로 만든다
		entityManager.createNativeQuery("update analysis set created_at = :createdAt where material_id = :materialId")
				.setParameter("createdAt", LocalDateTime.now().minusMinutes(10))
				.setParameter("materialId", stale)
				.executeUpdate();
		flushAndClear();

		materialService.deleteMaterial(userId, stale);
		materialService.deleteMaterial(userId, failed);
		flushAndClear();

		assertThat(materialRepository.findById(stale)).isEmpty();
		assertThat(materialRepository.findById(failed)).isEmpty();
	}

	@Test
	void deleteHidesOtherUsersMaterial() {
		Long materialId = upload(userId);
		flushAndClear();

		assertNotFound(() -> materialService.deleteMaterial(otherUserId, materialId));
		assertThat(materialRepository.findById(materialId)).isPresent();
	}

	private Long upload(Long ownerId) {
		return materialService.uploadImage(ownerId,
				new MockMultipartFile("file", "chart.png", "image/png", PNG_BYTES)).materialId();
	}

	// PDF 파싱 없이 목록 조회용 PDF 자료를 바로 저장한다
	private Long material(Long ownerId, SourceType sourceType, Integer pageCount) {
		return materialRepository.save(Material.builder()
				.user(userRepository.getReferenceById(ownerId))
				.sourceType(sourceType)
				.originalFilename("lecture.pdf")
				.filePath(UUID.randomUUID() + ".pdf")
				.mimeType("application/pdf")
				.fileSize(100)
				.pageCount(pageCount)
				.build()).getId();
	}

	private Long completed(Long materialId, VisualType visualType) {
		return completed(materialId, visualType, null);
	}

	private Long completed(Long materialId, VisualType visualType, String detail) {
		Material material = materialRepository.findById(materialId).orElseThrow();
		Analysis analysis = analysisRepository.save(new Analysis(material, Difficulty.NORMAL));
		analysis.complete(visualType, "해설 본문", detail, "model", "v1");
		material.changeTitle("제목");
		return materialId;
	}

	private Long pending(Long materialId) {
		analysisRepository.save(new Analysis(materialRepository.findById(materialId).orElseThrow(), Difficulty.NORMAL));
		return materialId;
	}

	private Long failed(Long materialId) {
		Analysis analysis = analysisRepository.save(
				new Analysis(materialRepository.findById(materialId).orElseThrow(), Difficulty.NORMAL));
		analysis.fail();
		return materialId;
	}

	private long totalOf(SourceType sourceType) {
		return materialService.getMaterials(userId, request(sourceType, null, null)).totalElements();
	}

	private static MaterialListRequest request(SourceType sourceType, Integer page, Integer size) {
		return new MaterialListRequest(sourceType, page, size);
	}

	private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call).extracting("resultCode").isEqualTo(ResultCode.RESOURCE_NOT_FOUND);
	}

	private User saveUser() {
		return userRepository.save(User.builder()
				.name("김눈치")
				.email("history-test-" + UUID.randomUUID() + "@example.com")
				.passwordHash("hash")
				.build());
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}

	private static Path createTempDir() {
		try {
			return Files.createTempDirectory("noonco-history-test");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
