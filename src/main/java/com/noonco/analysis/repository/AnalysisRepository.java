package com.noonco.analysis.repository;

import com.noonco.analysis.entity.Analysis;
import com.noonco.analysis.entity.AnalysisStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisRepository extends JpaRepository<Analysis, Long> {

	Optional<Analysis> findFirstByMaterialIdOrderByIdDesc(Long materialId);

	Optional<Analysis> findFirstByMaterialIdAndStatusOrderByIdDesc(Long materialId, AnalysisStatus status);
}
