package com.noonco.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

/**
 * API 를 호출하지 않고 요청 Content 구성만 확인한다.
 */
class GeminiLlmClientTest {

	private static final byte[] PDF_BYTES = "%PDF-1.7\n".getBytes();
	private static final byte[] PNG_BYTES = {(byte)0x89, 'P', 'N', 'G'};

	@Test
	void pdfInputBecomesApplicationPdfPartFollowedByPrompt() {
		List<Part> parts = parts(LlmRequest.ofFile("프롬프트", PDF_BYTES, "application/pdf"));

		assertThat(parts).hasSize(2);
		Blob blob = parts.get(0).inlineData().orElseThrow();
		assertThat(blob.mimeType()).contains("application/pdf");
		assertThat(blob.data()).hasValue(PDF_BYTES);
		assertThat(parts.get(1).text()).contains("프롬프트");
	}

	@Test
	void imageInputKeepsImageMimeType() {
		List<Part> parts = parts(LlmRequest.ofFile("프롬프트", PNG_BYTES, "image/png"));

		Blob blob = parts.get(0).inlineData().orElseThrow();
		assertThat(blob.mimeType()).contains("image/png");
		assertThat(blob.data()).hasValue(PNG_BYTES);
	}

	private static List<Part> parts(LlmRequest request) {
		Content content = GeminiLlmClient.toContent(request);
		return content.parts().orElseThrow();
	}
}
