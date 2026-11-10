package com.noonco.user.dto;

import java.math.BigDecimal;

import com.noonco.user.entity.Difficulty;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

// 보낸 필드만 변경한다. null 은 "변경 안 함".
public record UpdateSettingsRequest(
		Difficulty difficulty,

		@DecimalMin(value = "0.5", message = "말하기 속도는 0.5~2.0 사이로 입력해 주세요.")
		@DecimalMax(value = "2.0", message = "말하기 속도는 0.5~2.0 사이로 입력해 주세요.")
		@Digits(integer = 1, fraction = 1, message = "말하기 속도는 소수 첫째 자리까지 입력해 주세요.")
		BigDecimal speechRate
) {

	@AssertTrue(message = "변경할 설정을 입력해 주세요.")
	public boolean isAnyFieldPresent() {
		return difficulty != null || speechRate != null;
	}
}
