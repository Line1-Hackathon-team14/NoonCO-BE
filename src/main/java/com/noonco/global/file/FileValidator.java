package com.noonco.global.file;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.noonco.global.ResultCode;
import com.noonco.global.exception.BusinessException;

/**
 * 요청이 선언한 MIME 과 실제 파일 앞부분 시그니처가 모두 허용 형식이고 서로 같은지 검사한다.
 * 원본 파일명에 확장자가 있으면 그 확장자도 실제 형식과 맞아야 한다.
 * 확장자가 없거나 파일명이 비어 있으면(카메라 촬영 blob 등) 확장자 검사는 건너뛴다.
 * 원본 파일명은 검사에만 쓰고 저장 경로에는 쓰지 않는다.
 * 이미지 API 는 {JPEG, PNG}, PDF API 는 {PDF} 처럼 호출하는 쪽에서 허용 목록을 넘긴다.
 */
@Component
public class FileValidator {

	/**
	 * @return 검증된 실제 형식 (저장 확장자 결정에 사용)
	 * @throws BusinessException INVALID_FILE_TYPE
	 */
	public FileType validate(MultipartFile file, Set<FileType> allowed) {
		Optional<FileType> declared = FileType.fromMimeType(file.getContentType());
		Optional<FileType> actual = FileType.fromSignature(readHeader(file));

		if (declared.isEmpty() || actual.isEmpty() || declared.get() != actual.get() || !allowed.contains(actual.get())) {
			throw new BusinessException(ResultCode.INVALID_FILE_TYPE);
		}
		Optional<String> extension = extensionOf(file.getOriginalFilename());
		if (extension.isPresent() && !actual.get().acceptsExtension(extension.get())) {
			throw new BusinessException(ResultCode.INVALID_FILE_TYPE);
		}
		return actual.get();
	}

	/**
	 * 경로 구분자 뒤 마지막 이름에서 마지막 '.' 뒤를 확장자로 본다. 없거나 비어 있으면 empty.
	 */
	private static Optional<String> extensionOf(String originalFilename) {
		if (originalFilename == null) {
			return Optional.empty();
		}
		String name = originalFilename.substring(
				Math.max(originalFilename.lastIndexOf('/'), originalFilename.lastIndexOf('\\')) + 1);
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) {
			return Optional.empty();
		}
		return Optional.of(name.substring(dot + 1));
	}

	private static byte[] readHeader(MultipartFile file) {
		try (InputStream in = file.getInputStream()) {
			return in.readNBytes(FileType.MAX_SIGNATURE_LENGTH);
		} catch (IOException e) {
			throw new BusinessException(ResultCode.INVALID_FILE_TYPE);
		}
	}
}
