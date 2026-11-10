package com.noonco.material.repository;

import com.noonco.material.entity.Material;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialRepository extends JpaRepository<Material, Long> {

	// 삭제된 자료는 Material 의 @SQLRestriction 으로 제외된다
	Optional<Material> findByIdAndUserId(Long id, Long userId);
}
