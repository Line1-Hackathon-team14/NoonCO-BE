package com.noonco.auth.repository;

import java.util.Optional;

import com.noonco.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	// 삭제된 행 수를 돌려준다. 같은 토큰으로 동시에 재발급하면 한 요청만 1을 받는다.
	@Modifying(clearAutomatically = true)
	@Query("delete from RefreshToken r where r.tokenHash = :tokenHash")
	int deleteByTokenHash(String tokenHash);
}
