package com.noonco.material.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.noonco.ai.LlmProperties;
import com.noonco.analysis.entity.Analysis;
import com.noonco.analysis.entity.AnalysisStatus;
import com.noonco.analysis.repository.AnalysisRepository;
import com.noonco.global.ResultCode;
import com.noonco.global.exception.BusinessException;
import com.noonco.global.file.FileStorage;
import com.noonco.global.file.FileType;
import com.noonco.global.file.FileValidator;
import com.noonco.material.dto.MaterialCountResponse;
import com.noonco.material.dto.MaterialDetailResponse;
import com.noonco.material.dto.MaterialListRequest;
import com.noonco.material.dto.MaterialPageResponse;
import com.noonco.material.dto.MaterialUploadResponse;
import com.noonco.material.entity.Material;
import com.noonco.material.entity.SourceType;
import com.noonco.material.repository.MaterialRepository;
import com.noonco.user.repository.UserRepository;

@Service
@Transactional
public class MaterialService {

	private static final Set<FileType> IMAGE_TYPES = Set.of(FileType.JPEG, FileType.PNG);
	private static final Set<FileType> PDF_TYPES = Set.of(FileType.PDF);
	private static final int MAX_PDF_PAGES = 30;

	private final MaterialRepository materialRepository;
	private final AnalysisRepository analysisRepository;
	private final UserRepository userRepository;
	private final FileValidator fileValidator;
	private final FileStorage fileStorage;
	// 삭제 시 진행 중 분석 판단 기준. AnalysisService 와 같은 값이다
	private final Duration analysisTimeout;

	public MaterialService(MaterialRepository materialRepository, AnalysisRepository analysisRepository,
			UserRepository userRepository, FileValidator fileValidator, FileStorage fileStorage,
			LlmProperties llmProperties) {
		this.materialRepository = materialRepository;
		this.analysisRepository = analysisRepository;
		this.userRepository = userRepository;
		this.fileValidator = fileValidator;
		this.fileStorage = fileStorage;
		this.analysisTimeout = llmProperties.gemini().timeout();
	}

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
	 * 텍스트는 추출하지 않는다. 해설 생성 때 PDF 원본을 그대로 AI 에 보낸다.
	 * 검증을 저장보다 먼저 하므로 실패한 파일은 저장되지 않는다.
	 * @throws BusinessException INVALID_FILE_TYPE, PDF_UNREADABLE, PDF_TOO_MANY_PAGES
	 */
	public MaterialUploadResponse uploadPdf(Long userId, MultipartFile file) {
		FileType type = fileValidator.validate(file, PDF_TYPES);
		int pageCount = countPages(file);
		String key = storeFile(file, type);
		Material material = materialRepository.save(Material.builder()
				.user(userRepository.getReferenceById(userId))
				.sourceType(SourceType.PDF)
				.originalFilename(file.getOriginalFilename())
				.filePath(key)
				.mimeType(type.getMimeType())
				.fileSize((int)file.getSize())
				.pageCount(pageCount)
				.build());
		return MaterialUploadResponse.of(material);
	}

	/**
	 * 열람 암호가 걸렸거나(InvalidPasswordException) 손상된 PDF 는 열리지 않는다.
	 * 권한 암호만 걸린 PDF 는 열리므로 허용한다.
	 */
	private static int countPages(MultipartFile file) {
		int pageCount;
		try (InputStream in = file.getInputStream();
				PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(in))) {
			pageCount = document.getNumberOfPages();
		} catch (IOException e) {
			throw new BusinessException(ResultCode.PDF_UNREADABLE);
		}
		if (pageCount > MAX_PDF_PAGES) {
			throw new BusinessException(ResultCode.PDF_TOO_MANY_PAGES);
		}
		return pageCount;
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
	 * 완료된 해설이 있는 자료만 최신순으로 반환한다.
	 */
	@Transactional(readOnly = true)
	public MaterialPageResponse getMaterials(Long userId, MaterialListRequest request) {
		Pageable pageable = PageRequest.of(request.pageOrDefault(), request.sizeOrDefault());
		return MaterialPageResponse.of(
				materialRepository.findCompletedSummaries(userId, request.sourceType(), pageable));
	}

	/**
	 * getMaterials 와 같은 조건으로 센다.
	 */
	@Transactional(readOnly = true)
	public MaterialCountResponse getCounts(Long userId) {
		Map<SourceType, Long> counts = new EnumMap<>(SourceType.class);
		for (Object[] row : materialRepository.countCompletedBySourceType(userId)) {
			counts.put((SourceType)row[0], (Long)row[1]);
		}
		long image = counts.getOrDefault(SourceType.IMAGE, 0L);
		long pdf = counts.getOrDefault(SourceType.PDF, 0L);
		return new MaterialCountResponse(image + pdf, image, pdf);
	}

	/**
	 * 최신 COMPLETED 해설을 함께 반환한다. 스트림이 끊겼을 때 복구 조회에도 쓴다.
	 * @throws BusinessException RESOURCE_NOT_FOUND
	 */
	@Transactional(readOnly = true)
	public MaterialDetailResponse getMaterial(Long userId, Long materialId) {
		Material material = getOwnedMaterial(materialId, userId);
		Analysis completed = analysisRepository
				.findFirstByMaterialIdAndStatusOrderByIdDesc(materialId, AnalysisStatus.COMPLETED)
				.orElse(null);
		return MaterialDetailResponse.of(material, completed);
	}

	/**
	 * @throws BusinessException RESOURCE_NOT_FOUND - 자료가 없거나 저장소에 파일이 없을 때
	 */
	@Transactional(readOnly = true)
	public MaterialFile getFile(Long userId, Long materialId) {
		Material material = getOwnedMaterial(materialId, userId);
		return new MaterialFile(fileStorage.load(material.getFilePath()), material.getMimeType());
	}

	/**
	 * soft delete 만 하고 저장된 파일은 지우지 않는다.
	 * 해설 시작과 같은 material 행 잠금으로, 삭제와 해설 시작이 동시에 진행되지 않게 한다.
	 * @throws BusinessException RESOURCE_NOT_FOUND, ANALYSIS_IN_PROGRESS
	 */
	public void deleteMaterial(Long userId, Long materialId) {
		Material material = materialRepository.findByIdAndUserIdForUpdate(materialId, userId)
				.orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND));
		boolean inProgress = analysisRepository.findFirstByMaterialIdOrderByIdDesc(materialId)
				.map(latest -> latest.isInProgress(analysisTimeout))
				.orElse(false);
		if (inProgress) {
			throw new BusinessException(ResultCode.ANALYSIS_IN_PROGRESS);
		}
		material.delete();
	}

	public record MaterialFile(Resource resource, String mimeType) {
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
