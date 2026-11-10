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
			@DefaultValue("4096") Integer maxOutputTokens
	) {
	}

	public record Fake(
			@DefaultValue("100ms") Duration chunkDelay
	) {
	}
}
