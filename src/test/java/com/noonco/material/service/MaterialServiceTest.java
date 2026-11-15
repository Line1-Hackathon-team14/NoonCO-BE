package com.noonco.material.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;

import com.noonco.ai.LlmConfig;
import com.noonco.global.ResultCode;
import com.noonco.global.file.FileStorage;
import com.noonco.global.file.FileValidator;
import com.noonco.material.dto.MaterialUploadResponse;
import com.noonco.material.entity.Material;
import com.noonco.material.entity.SourceType;
import com.noonco.material.repository.MaterialRepository;
import com.noonco.user.entity.User;
import com.noonco.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

/**
 * 로컬 DB 와 임시 저장 폴더로 업로드·본인 자료 조회·실패 시 파일 정리를 확인한다.
 * 각 테스트 트랜잭션은 롤백되므로, 업로드한 파일도 테스트가 끝나면 함께 지워진다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ MaterialService.class, FileValidator.class, FileStorage.class, LlmConfig.class })
class MaterialServiceTest {

	private static final Path STORAGE_DIR = createTempDir();
	private static final byte[] PNG_BYTES = {(byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};

	@DynamicPropertySource
	static void storageDir(DynamicPropertyRegistry registry) {
		registry.add("file.storage-dir", STORAGE_DIR::toString);
	}

	@Autowired MaterialService materialService;
	@Autowired MaterialRepository materialRepository;
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
	void uploadImageStoresFileAndMaterial() {
		MaterialUploadResponse response = materialService.uploadImage(userId, png("chart.png"));
		flushAndClear();

		assertThat(response.materialId()).isNotNull();
		assertThat(response.sourceType()).isEqualTo(SourceType.IMAGE);
		assertThat(response.title()).isNull();
		assertThat(response.fileSize()).isEqualTo(PNG_BYTES.length);
		assertThat(response.pageCount()).isNull();
		assertThat(response.createdAt()).isNotNull();

		Material found = materialRepository.findById(response.materialId()).orElseThrow();
		assertThat(found.getUser().getId()).isEqualTo(userId);
		assertThat(found.getSourceType()).isEqualTo(SourceType.IMAGE);
		assertThat(found.getOriginalFilename()).isEqualTo("chart.png");
		assertThat(found.getMimeType()).isEqualTo("image/png");
		assertThat(found.getFileSize()).isEqualTo(PNG_BYTES.length);
		assertThat(found.getTitle()).isNull();
		assertThat(STORAGE_DIR.resolve(found.getFilePath())).isRegularFile().hasBinaryContent(PNG_BYTES);
	}

	@Test
	void uploadResponseCreatedAtMatchesStoredValue() {
		MaterialUploadResponse response = materialService.uploadImage(userId, png("chart.png"));
		flushAndClear();

		Material found = materialRepository.findById(response.materialId()).orElseThrow();
		assertThat(response.createdAt().getNano()).isZero();
		assertThat(response.createdAt()).isEqualTo(found.getCreatedAt());
	}

	@Test
	void invalidFileTypeIsRejectedWithoutStoringFile() {
		List<Path> before = storedFiles();
		MockMultipartFile pdf = new MockMultipartFile("file", "doc.pdf", "application/pdf", "%PDF-1.7\n".getBytes());

		assertThatThrownBy(() -> materialService.uploadImage(userId, pdf))
				.extracting("resultCode").isEqualTo(ResultCode.INVALID_FILE_TYPE);
		assertThat(storedFiles()).isEqualTo(before);
	}

	@Test
	void uploadPdfStoresFileAndPageCount() throws IOException {
		byte[] bytes = pdf(30);
		MaterialUploadResponse response = materialService.uploadPdf(userId, pdfFile("lecture.pdf", bytes));
		flushAndClear();

		assertThat(response.sourceType()).isEqualTo(SourceType.PDF);
		assertThat(response.title()).isNull();
		assertThat(response.pageCount()).isEqualTo(30);
		assertThat(response.fileSize()).isEqualTo(bytes.length);

		Material found = materialRepository.findById(response.materialId()).orElseThrow();
		assertThat(found.getSourceType()).isEqualTo(SourceType.PDF);
		assertThat(found.getOriginalFilename()).isEqualTo("lecture.pdf");
		assertThat(found.getMimeType()).isEqualTo("application/pdf");
		assertThat(found.getPageCount()).isEqualTo(30);
		assertThat(STORAGE_DIR.resolve(found.getFilePath())).isRegularFile().hasBinaryContent(bytes);
	}

	@Test
	void pdfOver30PagesIsRejectedWithoutStoring() throws IOException {
		assertPdfRejected(pdf(31), ResultCode.PDF_TOO_MANY_PAGES);
	}

	@Test
	void encryptedPdfIsRejectedWithoutStoring() throws IOException {
		assertPdfRejected(encryptedPdf(), ResultCode.PDF_UNREADABLE);
	}

	@Test
	void corruptedPdfIsRejectedWithoutStoring() {
		assertPdfRejected("%PDF-1.7\nbroken".getBytes(), ResultCode.PDF_UNREADABLE);
	}

	@Test
	void imageIsRejectedByPdfUpload() {
		List<Path> before = storedFiles();

		assertThatThrownBy(() -> materialService.uploadPdf(userId, png("chart.png")))
				.extracting("resultCode").isEqualTo(ResultCode.INVALID_FILE_TYPE);
		assertThat(storedFiles()).isEqualTo(before);
	}

	private void assertPdfRejected(byte[] bytes, ResultCode expected) {
		List<Path> before = storedFiles();
		long countBefore = materialRepository.count();

		assertThatThrownBy(() -> materialService.uploadPdf(userId, pdfFile("lecture.pdf", bytes)))
				.extracting("resultCode").isEqualTo(expected);
		assertThat(storedFiles()).isEqualTo(before);
		assertThat(materialRepository.count()).isEqualTo(countBefore);
	}

	@Test
	void storedFileIsDeletedWhenTransactionDoesNotCommit() {
		List<Path> before = storedFiles();
		// 없는 사용자를 참조해 INSERT 가 외래 키 제약으로 실패하게 만든다
		assertThatThrownBy(() -> materialService.uploadImage(-1L, png("chart.png")));
		assertThat(storedFiles()).hasSize(before.size() + 1);

		TestTransaction.end();

		assertThat(storedFiles()).isEqualTo(before);
	}

	@Test
	void getOwnedMaterialReturnsOwnMaterial() {
		Long materialId = materialService.uploadImage(userId, png("chart.png")).materialId();
		flushAndClear();

		assertThat(materialService.getOwnedMaterial(materialId, userId).getId()).isEqualTo(materialId);
	}

	@Test
	void getOwnedMaterialHidesOtherUsersDeletedAndMissingMaterials() {
		Long materialId = materialService.uploadImage(userId, png("chart.png")).materialId();
		Long deletedId = materialService.uploadImage(userId, png("old.png")).materialId();
		materialRepository.findById(deletedId).orElseThrow().delete();
		flushAndClear();

		assertNotFound(materialId, otherUserId);
		assertNotFound(deletedId, userId);
		assertNotFound(-1L, userId);
	}

	private void assertNotFound(Long materialId, Long userId) {
		assertThatThrownBy(() -> materialService.getOwnedMaterial(materialId, userId))
				.extracting("resultCode").isEqualTo(ResultCode.RESOURCE_NOT_FOUND);
	}

	private User saveUser() {
		// 로컬 DB 에 있을 수 있는 데이터와 겹치지 않는 이메일
		return userRepository.save(User.builder()
				.name("김눈치")
				.email("material-test-" + UUID.randomUUID() + "@example.com")
				.passwordHash("hash")
				.build());
	}

	private static MockMultipartFile png(String filename) {
		return new MockMultipartFile("file", filename, "image/png", PNG_BYTES);
	}

	private static MockMultipartFile pdfFile(String filename, byte[] bytes) {
		return new MockMultipartFile("file", filename, "application/pdf", bytes);
	}

	private static byte[] pdf(int pages) throws IOException {
		try (PDDocument document = new PDDocument()) {
			for (int i = 0; i < pages; i++) {
				document.addPage(new PDPage());
			}
			return save(document);
		}
	}

	// 열람 암호(user password)가 걸린 PDF
	private static byte[] encryptedPdf() throws IOException {
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage());
			StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "user", new AccessPermission());
			policy.setEncryptionKeyLength(128);
			document.protect(policy);
			return save(document);
		}
	}

	private static byte[] save(PDDocument document) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		document.save(out);
		return out.toByteArray();
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}

	private static List<Path> storedFiles() {
		try (Stream<Path> files = Files.list(STORAGE_DIR)) {
			return files.sorted().toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Path createTempDir() {
		try {
			return Files.createTempDirectory("noonco-material-test");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
