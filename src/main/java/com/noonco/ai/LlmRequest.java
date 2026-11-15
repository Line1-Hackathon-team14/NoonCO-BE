package com.noonco.ai;

import java.util.Objects;

public record LlmRequest(String prompt, LlmInput input) {

	public LlmRequest {
		Objects.requireNonNull(prompt, "prompt");
		Objects.requireNonNull(input, "input");
	}

	public static LlmRequest ofFile(String prompt, byte[] bytes, String mimeType) {
		return new LlmRequest(prompt, new LlmInput.File(bytes, mimeType));
	}
}
