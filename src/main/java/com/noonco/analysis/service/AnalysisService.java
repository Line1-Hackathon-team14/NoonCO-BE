package com.noonco.analysis.service;

import java.time.Duration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.noonco.ai.LlmProperties;
import com.noonco.ai.Prompt;
import com.noonco.ai.PromptLoader;
import com.noonco.analysis.entity.Analysis;
import com.noonco.analysis.entity.AnalysisStatus;
import com.noonco.analysis.entity.VisualType;
import com.noonco.analysis.repository.AnalysisRepository;
import com.noonco.global.ResultCode;
import com.noonco.global.exception.BusinessException;
import com.noonco.material.entity.Material;
import com.noonco.material.entity.SourceType;
import com.noonco.material.repository.MaterialRepository;
import com.noonco.user.entity.Difficulty;
import com.noonco.user.repository.UserSettingsRepository;

/**
 * 해설 생성의 시작·완료·실패 상태를 각각 짧은 트랜잭션으로 저장한다.
 * LLM 호출과 스트림 전송은 트랜잭션 밖({@link AnalysisStreamService})에서 한다.
 */
@Service
@Transactional
public class AnalysisService {

	private final MaterialRepository materialRepository;
	private final AnalysisRepository analysisRepository;
	private final UserSettingsRepository userSettingsRepository;
	private final PromptLoader promptLoader;
	// PENDING 이 이 시간 안이면 진행 중으로 본다. LLM 호출 전체 제한 시간과 같다
	private final Duration analysisTimeout;

	public AnalysisService(MaterialRepository materialRepository, AnalysisRepository analysisRepository,
			UserSettingsRepository userSettingsRepository, PromptLoader promptLoader, LlmProperties llmProperties) {
		this.materialRepository = materialRepository;
		this.analysisRepository = analysisRepository;
		this.userSettingsRepository = userSettingsRepository;
		this.promptLoader = promptLoader;
		this.analysisTimeout = llmProperties.gemini().timeout();
	}

	/**
	 * 해설을 시작할 수 있는지 확인하고 PENDING analysis 를 만든다.
	 * 같은 자료에 대한 동시 요청은 material 행 잠금으로 줄을 세운다.
	 * 잠금 조회를 이 트랜잭션의 첫 조회로 해야, 잠금을 얻은 뒤의 조회가 앞 요청이 커밋한 analysis 를 본다(MySQL REPEATABLE READ).
	 *
	 * @throws BusinessException RESOURCE_NOT_FOUND, ANALYSIS_ALREADY_COMPLETED, ANALYSIS_IN_PROGRESS
	 */
	public AnalysisStart start(Long userId, Long materialId) {
		// 타인 자료, 삭제된 자료, 없는 자료를 구분하지 않는다 (MaterialService.getOwnedMaterial 과 같은 기준)
		Material material = materialRepository.findByIdAndUserIdForUpdate(materialId, userId)
				.orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND));

		analysisRepository.findFirstByMaterialIdOrderByIdDesc(materialId).ifPresent(this::checkStartable);

		Difficulty difficulty = userSettingsRepository.findById(userId)
				.orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND))
				.getDifficulty();
		Prompt prompt = promptLoader.get(material.getSourceType(), difficulty);
		Analysis analysis = analysisRepository.save(new Analysis(material, difficulty));
		return new AnalysisStart(analysis.getId(), material.getId(), difficulty, material.getSourceType(),
				material.getFilePath(), material.getMimeType(), prompt);
	}

	/**
	 * 자료가 그 사이 삭제됐으면 제목은 저장하지 않는다.
	 */
	public void complete(Long analysisId, Long materialId, VisualType visualType, String title, String summary,
			String model, String promptVersion) {
		Analysis analysis = analysisRepository.findById(analysisId).orElseThrow();
		analysis.complete(visualType, summary, model, promptVersion);
		materialRepository.findById(materialId).ifPresent(material -> material.changeTitle(title));
	}

	/**
	 * 아직 PENDING 인 경우에만 FAILED 로 바꾼다.
	 */
	public void fail(Long analysisId) {
		analysisRepository.findById(analysisId)
				.filter(analysis -> analysis.getStatus() == AnalysisStatus.PENDING)
				.ifPresent(Analysis::fail);
	}

	private void checkStartable(Analysis latest) {
		if (latest.getStatus() == AnalysisStatus.COMPLETED) {
			throw new BusinessException(ResultCode.ANALYSIS_ALREADY_COMPLETED);
		}
		if (latest.isInProgress(analysisTimeout)) {
			throw new BusinessException(ResultCode.ANALYSIS_IN_PROGRESS);
		}
	}

	/**
	 * 스트림 전송에 필요한 값. 트랜잭션 밖에서 쓰므로 엔티티 대신 값만 담는다.
	 */
	public record AnalysisStart(Long analysisId, Long materialId, Difficulty difficulty, SourceType sourceType,
			String filePath, String mimeType, Prompt prompt) {
	}
}
