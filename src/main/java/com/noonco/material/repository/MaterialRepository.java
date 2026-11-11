package com.noonco.material.repository;

import com.noonco.material.dto.MaterialSummaryResponse;
import com.noonco.material.entity.Material;
import com.noonco.material.entity.SourceType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialRepository extends JpaRepository<Material, Long> {

	// 삭제된 자료는 Material 의 @SQLRestriction 으로 제외된다
	Optional<Material> findByIdAndUserId(Long id, Long userId);

	// SELECT ... FOR UPDATE. 같은 자료의 분석 시작·삭제를 직렬화할 때 쓴다
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from Material m where m.id = :id and m.user.id = :userId")
	Optional<Material> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	/**
	 * COMPLETED 분석이 있는 자료만 최신순으로 조회한다.
	 * 완료된 자료는 다시 분석할 수 없어 자료당 COMPLETED 분석은 1건뿐이므로 join 해도 중복되지 않는다.
	 * join 에는 @SQLRestriction 이 적용되지 않을 수 있어 삭제 조건을 직접 적는다.
	 */
	@Query(value = """
			select new com.noonco.material.dto.MaterialSummaryResponse(
				m.id, m.sourceType, m.title, a.visualType, m.pageCount, a.difficulty, m.createdAt)
			from Analysis a join a.material m
			where m.user.id = :userId and m.deletedAt is null
				and a.status = com.noonco.analysis.entity.AnalysisStatus.COMPLETED
				and (:sourceType is null or m.sourceType = :sourceType)
			order by m.createdAt desc, m.id desc""",
			countQuery = """
			select count(m) from Analysis a join a.material m
			where m.user.id = :userId and m.deletedAt is null
				and a.status = com.noonco.analysis.entity.AnalysisStatus.COMPLETED
				and (:sourceType is null or m.sourceType = :sourceType)""")
	Page<MaterialSummaryResponse> findCompletedSummaries(@Param("userId") Long userId,
			@Param("sourceType") SourceType sourceType, Pageable pageable);

	/**
	 * findCompletedSummaries 와 같은 조건으로 유형별 개수를 센다. 행은 [SourceType, Long] 이다.
	 */
	@Query("""
			select m.sourceType, count(m) from Analysis a join a.material m
			where m.user.id = :userId and m.deletedAt is null
				and a.status = com.noonco.analysis.entity.AnalysisStatus.COMPLETED
			group by m.sourceType""")
	List<Object[]> countCompletedBySourceType(@Param("userId") Long userId);
}
