package com.noonco.user.dto;

import java.math.BigDecimal;

import com.noonco.user.entity.Difficulty;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

// 보낸 필드만 변경한다. null 은 "변경 안 함".
@Schema(description = "설정 변경 요청. 보낸 필드만 변경하며, 최소 1개는 보내야 한다")
public record UpdateSettingsRequest(
		@Schema(description = "해설 난이도. 이후 새 분석에만 적용된다", example = "HARD", nullable = true)
		Difficulty difficulty,

		@Schema(description = "말하기 속도. 0.5~2.0, 소수 첫째 자리까지", example = "1.2", nullable = true)
		@DecimalMin(value = "0.5", message = "말하기 속도는 0.5~2.0 사이로 입력해 주세요.")
		@DecimalMax(value = "2.0", message = "말하기 속도는 0.5~2.0 사이로 입력해 주세요.")
		@Digits(integer = 1, fraction = 1, message = "말하기 속도는 소수 첫째 자리까지 입력해 주세요.")
		BigDecimal speechRate
) {

	@Schema(hidden = true)
	@AssertTrue(message = "변경할 설정을 입력해 주세요.")
	public boolean isAnyFieldPresent() {
		return difficulty != null || speechRate != null;
	}
}
