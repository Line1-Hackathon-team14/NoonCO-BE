package com.noonco.material.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.noonco.global.ResultCode;
import com.noonco.global.exception.BusinessException;
import com.noonco.global.file.FileStorage;
import com.noonco.global.file.FileType;
import com.noonco.global.file.FileValidator;
import com.noonco.material.dto.MaterialUploadResponse;
import com.noonco.material.entity.Material;
import com.noonco.material.entity.SourceType;
import com.noonco.material.repository.MaterialRepository;
import com.noonco.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class MaterialService {

	private static final Set<FileType> IMAGE_TYPES = Set.of(FileType.JPEG, FileType.PNG);

	private final MaterialRepository materialRepository;
	private final UserRepository userRepository;
	private final FileValidator fileValidator;
	private final FileStorage fileStorage;

	/**
	 * title 은 해설 생성(분석) 뒤에 채우므로 여기서는 비워 둔다.
	 * @throws BusinessException INVALID_FILE_TYPE
	 */
	public MaterialUploadResponse uploadImage(Long userId, MultipartFile file) {
		FileType type = fileValidator.validate(file, IMAGE_TYPES);
		String key = storeFile(file, type);
		Material material = materialRepository.save(Material.builder()
				.user(userRepository.getReferenceById(userId))
				.sourceType(SourceType.IMAGE)
				.originalFilename(file.getOriginalFilename())
				.filePath(key)
				.mimeType(type.getMimeType())
				.fileSize((int)file.getSize())
				.build());
		return MaterialUploadResponse.of(material);
	}

	/**
	 * 타인 자료, 삭제된 자료, 없는 자료를 구분하지 않고 모두 RESOURCE_NOT_FOUND 로 응답한다.
	 * @throws BusinessException RESOURCE_NOT_FOUND
	 */
	@Transactional(readOnly = true)
	public Material getOwnedMaterial(Long materialId, Long userId) {
		return materialRepository.findByIdAndUserId(materialId, userId)
				.orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND));
	}

	/**
	 * 파일을 저장하고, 이 트랜잭션이 커밋되지 않으면(DB 저장·커밋 실패) 저장한 파일을 지운다.
	 * PDF 업로드 등 파일을 저장하는 다른 기능도 이 메서드를 쓴다.
	 */
	private String storeFile(MultipartFile file, FileType type) {
		String key;
		try (InputStream in = file.getInputStream()) {
			key = fileStorage.save(in, type);
		} catch (IOException e) {
			throw new UncheckedIOException("업로드 파일을 읽지 못했습니다.", e);
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) {
					fileStorage.delete(key);
				}
			}
		});
		return key;
	}
}
