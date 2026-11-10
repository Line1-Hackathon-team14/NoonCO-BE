package com.noonco.material.repository;

import com.noonco.material.entity.Material;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialRepository extends JpaRepository<Material, Long> {

	// 삭제된 자료는 Material 의 @SQLRestriction 으로 제외된다
	Optional<Material> findByIdAndUserId(Long id, Long userId);

	// SELECT ... FOR UPDATE. 같은 자료의 분석 시작을 직렬화할 때 쓴다
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from Material m where m.id = :id and m.user.id = :userId")
	Optional<Material> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
