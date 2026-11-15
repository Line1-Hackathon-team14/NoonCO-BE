package com.noonco.user.entity;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "해설 난이도: EASY(쉬움), NORMAL(보통), HARD(어려움)")
public enum Difficulty {
	EASY, NORMAL, HARD
}
