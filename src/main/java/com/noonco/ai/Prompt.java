package com.noonco.ai;

import java.util.Objects;

/**
 * 프롬프트 파일에서 읽은 본문과 버전.
 * content 는 LlmRequest.prompt 에, version 은 analysis.prompt_version 에 저장한다.
 */
public record Prompt(String content, String version) {

	public Prompt {
		Objects.requireNonNull(content, "content");
		Objects.requireNonNull(version, "version");
	}
}
