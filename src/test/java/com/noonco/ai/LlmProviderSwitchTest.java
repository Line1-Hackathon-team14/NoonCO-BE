package com.noonco.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * DB 없이 ai 패키지 빈만 띄워서 llm.provider 값에 따른 구현 전환을 검증한다.
 */
class LlmProviderSwitchTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(LlmConfig.class, GeminiLlmClient.class, FakeLlmClient.class);

	@Test
	void fakeProviderRegistersFakeClient() {
		contextRunner.withPropertyValues("llm.provider=fake")
				.run(context -> assertThat(context.getBean(LlmClient.class)).isInstanceOf(FakeLlmClient.class));
	}

	@Test
	void geminiProviderRegistersGeminiClientWithConfiguredValues() {
		contextRunner.withPropertyValues(
						"llm.provider=gemini",
						"llm.gemini.api-key=dummy-key",
						"llm.gemini.model=gemini-test-model",
						"llm.gemini.temperature=0.2",
						"llm.gemini.max-output-tokens=128")
				.run(context -> {
					assertThat(context.getBean(LlmClient.class)).isInstanceOf(GeminiLlmClient.class);
					LlmProperties.Gemini gemini = context.getBean(LlmProperties.class).gemini();
					assertThat(gemini.model()).isEqualTo("gemini-test-model");
					assertThat(gemini.temperature()).isEqualTo(0.2f);
					assertThat(gemini.maxOutputTokens()).isEqualTo(128);
				});
	}

	@Test
	void geminiProviderWithoutApiKeyFailsFast() {
		contextRunner.withPropertyValues("llm.provider=gemini")
				.run(context -> assertThat(context).hasFailed());
	}
}
