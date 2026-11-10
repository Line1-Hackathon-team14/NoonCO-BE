package com.noonco.ai;

import java.util.Objects;

/**
 * LLM 에 보내는 자료. 이미지 1장 또는 PDF 에서 추출한 텍스트 중 하나다.
 */
public sealed interface LlmInput permits LlmInput.Image, LlmInput.Text {

	record Image(byte[] bytes, String mimeType) implements LlmInput {

		public Image {
			Objects.requireNonNull(bytes, "bytes");
			Objects.requireNonNull(mimeType, "mimeType");
			if (bytes.length == 0) {
				throw new IllegalArgumentException("이미지 데이터가 비어 있습니다.");
			}
		}
	}

	record Text(String content) implements LlmInput {

		public Text {
			Objects.requireNonNull(content, "content");
		}
	}
}
