package com.noonco.global.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.noonco.global.ResultCode;
import com.noonco.global.exception.BusinessException;

/**
 * 업로드 파일을 "UUID.확장자" 이름으로 저장 폴더에 바로 저장하고, 그 이름(저장소 키)을 돌려준다.
 * DB(material.file_path)에는 전체 경로가 아니라 이 키만 저장한다.
 * 원본 파일명은 경로에 쓰지 않으며, 확장자는 검증된 {@link FileType} 에서 정한다.
 */
@Component
public class FileStorage {

	private static final Pattern KEY_PATTERN = Pattern.compile(
			"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|pdf)$");

	private final Path baseDir;

	public FileStorage(@Value("${file.storage-dir:./data/files}") Path storageDir) {
		try {
			this.baseDir = Files.createDirectories(storageDir.toAbsolutePath().normalize()).toRealPath();
		} catch (IOException e) {
			throw new IllegalStateException("파일 저장 폴더를 만들지 못했습니다: " + storageDir, e);
		}
	}

	/**
	 * @param type {@link FileValidator} 로 검증된 실제 형식
	 * @return 저장소 키 (예: "3f2a...c1.jpg")
	 */
	public String save(InputStream content, FileType type) {
		String key = UUID.randomUUID() + "." + type.getExtension();
		Path target = baseDir.resolve(key);
		try {
			// 같은 이름이 이미 있으면 덮어쓰지 않고 실패한다
			Files.copy(content, target);
		} catch (IOException e) {
			deleteQuietly(target);
			throw new UncheckedIOException("파일을 저장하지 못했습니다: " + key, e);
		}
		return key;
	}

	/**
	 * @throws BusinessException RESOURCE_NOT_FOUND - 키 형식이 틀렸거나, 저장 폴더 밖을 가리키거나, 파일이 없을 때
	 */
	public Resource load(String key) {
		return new FileSystemResource(resolveExisting(key));
	}

	private Path resolveExisting(String key) {
		if (key == null || !KEY_PATTERN.matcher(key).matches()) {
			throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND);
		}
		Path path = baseDir.resolve(key).normalize();
		if (!path.startsWith(baseDir) || !Files.isRegularFile(path)) {
			throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND);
		}
		try {
			// 심볼릭 링크로 저장 폴더 밖을 가리키는 경우도 막는다
			Path real = path.toRealPath();
			if (!real.startsWith(baseDir)) {
				throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND);
			}
			return real;
		} catch (IOException e) {
			throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND);
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// 저장 실패 원인 예외를 우선한다
		}
	}
}
