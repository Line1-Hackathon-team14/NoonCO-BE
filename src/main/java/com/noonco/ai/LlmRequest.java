package com.noonco.ai;

import java.util.Objects;

public record LlmRequest(String prompt, LlmInput input) {

	public LlmRequest {
		Objects.requireNonNull(prompt, "prompt");
		Objects.requireNonNull(input, "input");
	}

	public static LlmRequest ofImage(String prompt, byte[] imageBytes, String mimeType) {
		return new LlmRequest(prompt, new LlmInput.Image(imageBytes, mimeType));
	}

	public static LlmRequest ofText(String prompt, String text) {
		return new LlmRequest(prompt, new LlmInput.Text(text));
	}
}
