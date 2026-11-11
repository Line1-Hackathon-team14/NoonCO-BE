package com.noonco.ai;

import java.util.Objects;

/**
 * LLM 에 보내는 자료. 이미지 1장 또는 PDF 원본 파일을 mimeType 과 함께 그대로 보낸다.
 */
public sealed interface LlmInput permits LlmInput.File {

	record File(byte[] bytes, String mimeType) implements LlmInput {

		public File {
			Objects.requireNonNull(bytes, "bytes");
			Objects.requireNonNull(mimeType, "mimeType");
			if (bytes.length == 0) {
				throw new IllegalArgumentException("파일 데이터가 비어 있습니다.");
			}
		}
	}
}
