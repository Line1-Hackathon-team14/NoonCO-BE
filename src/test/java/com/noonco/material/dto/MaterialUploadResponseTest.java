package com.noonco.material.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.noonco.material.entity.Material;
import com.noonco.material.entity.SourceType;

class MaterialUploadResponseTest {

	// MySQL DATETIME 이 소수 초를 저장할 때와 같은 결과여야 한다
	@ParameterizedTest
	@CsvSource({
			"2026-11-11T00:49:57.6854565, 2026-11-11T00:49:58",
			"2026-11-11T00:49:57.4999999, 2026-11-11T00:49:57",
			"2026-11-11T00:49:57.5,       2026-11-11T00:49:58",
			"2026-11-11T00:49:57,         2026-11-11T00:49:57",
			"2026-11-11T23:59:59.9,       2026-11-12T00:00:00"
	})
	void createdAtIsRoundedToSecondsLikeDatetimeColumn(LocalDateTime saved, LocalDateTime expected) {
		Material material = Material.builder().sourceType(SourceType.IMAGE).build();
		ReflectionTestUtils.setField(material, "createdAt", saved);

		assertThat(MaterialUploadResponse.of(material).createdAt()).isEqualTo(expected);
	}
}
