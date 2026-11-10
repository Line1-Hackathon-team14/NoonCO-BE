package com.noonco.global.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;

import com.noonco.global.ResultCode;
import com.noonco.global.exception.BusinessException;

/**
 * DB 없이 임시 폴더에 FileStorage 를 띄워 저장·읽기·키 검증을 확인한다.
 */
class FileStorageTest {

	private static final String KEY_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|pdf)";

	@TempDir
	Path dir;

	@Test
	void savedFileReadsBackWithSameContent() throws IOException {
		FileStorage storage = new FileStorage(dir);

		String key = storage.save(new ByteArrayInputStream(FileValidatorTest.PDF_BYTES), FileType.PDF);

		assertThat(readAll(storage.load(key))).isEqualTo(FileValidatorTest.PDF_BYTES);
	}

	@Test
	void keyAndPathUseUuidAndVerifiedExtensionNotOriginalName() throws IOException {
		FileStorage storage = new FileStorage(dir);
		// 원본 파일명(한글·특수문자 포함)은 저장 경로에 쓰이지 않아야 한다
		MockMultipartFile upload = new MockMultipartFile("file", "내-과제_report.PNG", "image/png", FileValidatorTest.PNG_BYTES);
		FileType type = new FileValidator().validate(upload, EnumSet.of(FileType.JPEG, FileType.PNG));

		String key = storage.save(upload.getInputStream(), type);

		assertThat(key).matches(KEY_REGEX).endsWith(".png").doesNotContain("report").doesNotContain("과제");
		List<Path> stored = listFiles();
		assertThat(stored).hasSize(1);
		Path storedPath = stored.get(0);
		assertThat(storedPath.getFileName().toString()).isEqualTo(key);
		assertThat(storedPath.toString()).doesNotContain("report").doesNotContain("과제");
		assertThat(storage.load(key).getFile().toPath()).isEqualTo(storedPath.toRealPath());
	}

	@Test
	void eachSaveGetsDifferentKey() {
		FileStorage storage = new FileStorage(dir);

		String first = storage.save(new ByteArrayInputStream(FileValidatorTest.JPEG_BYTES), FileType.JPEG);
		String second = storage.save(new ByteArrayInputStream(FileValidatorTest.JPEG_BYTES), FileType.JPEG);

		assertThat(first).isNotEqualTo(second).endsWith(".jpg");
	}

	@Test
	void createsStorageDirIfMissing() {
		Path nested = dir.resolve("a/b/files");

		new FileStorage(nested);

		assertThat(nested).isDirectory();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"../secret.txt",
			"../3f2a1b4c-0000-4000-8000-0000000000c1.jpg",
			"..\\3f2a1b4c-0000-4000-8000-0000000000c1.jpg",
			"sub/3f2a1b4c-0000-4000-8000-0000000000c1.jpg",
			"/etc/passwd",
			"C:\\Windows\\win.ini",
			"3f2a1b4c-0000-4000-8000-0000000000c1.exe",
			"3f2a1b4c-0000-4000-8000-0000000000c1.jpg/../../x.jpg",
			"report.jpg",
			""
	})
	void rejectsMalformedOrTraversalKeys(String key) throws IOException {
		FileStorage storage = new FileStorage(dir.resolve("files"));
		// 저장 폴더 바깥에 실제 파일을 둬서, 검증이 없다면 읽혀 버리는 상황을 만든다
		Files.write(dir.resolve("secret.txt"), new byte[] {1});
		Files.write(dir.resolve("3f2a1b4c-0000-4000-8000-0000000000c1.jpg"), FileValidatorTest.JPEG_BYTES);

		assertNotFound(storage, key);
	}

	@Test
	void rejectsNullKey() {
		assertNotFound(new FileStorage(dir), null);
	}

	@Test
	void rejectsWellFormedKeyThatDoesNotExist() {
		assertNotFound(new FileStorage(dir), "3f2a1b4c-0000-4000-8000-0000000000c1.jpg");
	}

	@Test
	void rejectsSymlinkPointingOutsideStorageDir() throws IOException {
		Path storageDir = dir.resolve("files");
		FileStorage storage = new FileStorage(storageDir);
		Path outside = Files.write(dir.resolve("outside.jpg"), FileValidatorTest.JPEG_BYTES);
		String key = "3f2a1b4c-0000-4000-8000-0000000000c1.jpg";
		try {
			Files.createSymbolicLink(storageDir.resolve(key), outside);
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			// Windows 권한 부족 등으로 링크를 만들 수 없는 환경에서는 건너뛴다
			Assumptions.abort("심볼릭 링크를 만들 수 없는 환경: " + e);
		}

		assertNotFound(storage, key);
	}

	@Test
	void registersAsBeanUsingStorageDirProperty() {
		Path configured = dir.resolve("configured");
		new ApplicationContextRunner()
				.withBean(FileStorage.class)
				.withPropertyValues("file.storage-dir=" + configured)
				.run(context -> {
					assertThat(context).hasNotFailed();
					String key = context.getBean(FileStorage.class)
							.save(new ByteArrayInputStream(FileValidatorTest.PNG_BYTES), FileType.PNG);
					assertThat(configured.resolve(key)).isRegularFile();
				});
	}

	@Test
	void deleteRemovesSavedFile() throws IOException {
		FileStorage storage = new FileStorage(dir);
		String key = storage.save(new ByteArrayInputStream(FileValidatorTest.PNG_BYTES), FileType.PNG);

		storage.delete(key);

		assertThat(listFiles()).isEmpty();
		assertNotFound(storage, key);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"../outside.jpg",
			"../3f2a1b4c-0000-4000-8000-0000000000c1.jpg",
			"3f2a1b4c-0000-4000-8000-0000000000c2.jpg",
			""
	})
	void deleteIgnoresMalformedTraversalOrMissingKeys(String key) throws IOException {
		FileStorage storage = new FileStorage(dir.resolve("files"));
		Path outside = Files.write(dir.resolve("outside.jpg"), FileValidatorTest.JPEG_BYTES);
		Path outsideWithKeyName = Files.write(dir.resolve("3f2a1b4c-0000-4000-8000-0000000000c1.jpg"), FileValidatorTest.JPEG_BYTES);

		storage.delete(key);

		assertThat(outside).isRegularFile();
		assertThat(outsideWithKeyName).isRegularFile();
	}

	@Test
	void deleteIgnoresNullKey() {
		new FileStorage(dir).delete(null);
	}

	private static void assertNotFound(FileStorage storage, String key) {
		assertThatThrownBy(() -> storage.load(key))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException)e).getResultCode())
				.isEqualTo(ResultCode.RESOURCE_NOT_FOUND);
	}

	private List<Path> listFiles() throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			return files.toList();
		}
	}

	private static byte[] readAll(Resource resource) throws IOException {
		try (InputStream in = resource.getInputStream()) {
			return in.readAllBytes();
		}
	}
}
