package com.noonco.ai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("llm")
public record LlmProperties(
		@DefaultValue("fake") String provider,
		@DefaultValue Gemini gemini,
		@DefaultValue Fake fake
) {

	public record Gemini(
			String apiKey,
			@DefaultValue("gemini-3.8-flash") String model,
			@DefaultValue("1.0") Float temperature,
			@DefaultValue("4096") Integer maxOutputTokens,
			// 연결·재시도·스트림 수신 전체에 걸리는 시간 제한
			@DefaultValue("120s") Duration timeout,
			// 첫 시도를 포함한 총 시도 횟수 (1 이면 재시도 없음)
			@DefaultValue("2") Integer retryAttempts
	) {
	}

	public record Fake(
			@DefaultValue("100ms") Duration chunkDelay
	) {
	}
}
