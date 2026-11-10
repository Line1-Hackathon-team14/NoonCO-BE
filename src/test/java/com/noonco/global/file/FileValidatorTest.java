package com.noonco.global.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.noonco.global.ResultCode;
import com.noonco.global.exception.BusinessException;

/**
 * DB 없이 FileValidator 만으로 MIME·시그니처 검증을 확인한다.
 */
class FileValidatorTest {

	static final byte[] JPEG_BYTES = {(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F'};
	static final byte[] PNG_BYTES = {(byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};
	static final byte[] PDF_BYTES = "%PDF-1.7\n%本문".getBytes(StandardCharsets.UTF_8);

	private static final Set<FileType> IMAGE_TYPES = EnumSet.of(FileType.JPEG, FileType.PNG);
	private static final Set<FileType> PDF_TYPES = EnumSet.of(FileType.PDF);

	private final FileValidator validator = new FileValidator();

	@Test
	void acceptsJpegPngForImageAndPdfForPdf() {
		assertThat(validator.validate(file("a.jpg", "image/jpeg", JPEG_BYTES), IMAGE_TYPES)).isEqualTo(FileType.JPEG);
		assertThat(validator.validate(file("a.png", "image/png", PNG_BYTES), IMAGE_TYPES)).isEqualTo(FileType.PNG);
		assertThat(validator.validate(file("a.pdf", "application/pdf", PDF_BYTES), PDF_TYPES)).isEqualTo(FileType.PDF);
	}

	@Test
	void ignoresMimeParametersAndCase() {
		assertThat(validator.validate(file("a.png", "IMAGE/PNG; charset=binary", PNG_BYTES), IMAGE_TYPES))
				.isEqualTo(FileType.PNG);
	}

	@Test
	void decidesTypeFromContentNotFilename() {
		// 파일명은 .pdf 지만 선언 MIME 과 내용이 모두 PNG 이면 PNG 로 판정
		assertThat(validator.validate(file("photo.pdf", "image/png", PNG_BYTES), IMAGE_TYPES)).isEqualTo(FileType.PNG);
	}

	@Test
	void rejectsPngContentSentAsJpeg() {
		assertInvalid(file("photo.jpg", "image/jpeg", PNG_BYTES), IMAGE_TYPES);
	}

	@Test
	void rejectsTextFileSentAsPng() {
		byte[] text = "그냥 텍스트 파일입니다.".getBytes(StandardCharsets.UTF_8);
		assertInvalid(file("note.png", "image/png", text), IMAGE_TYPES);
	}

	@Test
	void rejectsTypeNotInAllowedList() {
		assertInvalid(file("doc.pdf", "application/pdf", PDF_BYTES), IMAGE_TYPES);
		assertInvalid(file("a.png", "image/png", PNG_BYTES), PDF_TYPES);
	}

	@Test
	void rejectsUnsupportedOrMissingMime() {
		assertInvalid(file("a.gif", "image/gif", PNG_BYTES), IMAGE_TYPES);
		assertInvalid(file("a.png", null, PNG_BYTES), IMAGE_TYPES);
	}

	@Test
	void rejectsEmptyOrTooShortFile() {
		assertInvalid(file("a.png", "image/png", new byte[0]), IMAGE_TYPES);
		assertInvalid(file("a.png", "image/png", new byte[] {(byte)0x89, 'P', 'N'}), IMAGE_TYPES);
	}

	private void assertInvalid(MockMultipartFile file, Set<FileType> allowed) {
		assertThatThrownBy(() -> validator.validate(file, allowed))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException)e).getResultCode())
				.isEqualTo(ResultCode.INVALID_FILE_TYPE);
	}

	private static MockMultipartFile file(String name, String contentType, byte[] content) {
		return new MockMultipartFile("file", name, contentType, content);
	}
}
