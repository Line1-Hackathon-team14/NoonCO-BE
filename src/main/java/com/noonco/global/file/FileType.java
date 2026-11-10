package com.noonco.global.file;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 업로드를 허용하는 파일 형식.
 * 저장 확장자는 원본 파일명이 아니라 이 값에서 정한다.
 */
@Getter
@RequiredArgsConstructor
public enum FileType {

	JPEG("image/jpeg", "jpg", new byte[] {(byte)0xFF, (byte)0xD8, (byte)0xFF}),
	PNG("image/png", "png", new byte[] {(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}),
	PDF("application/pdf", "pdf", new byte[] {0x25, 0x50, 0x44, 0x46, 0x2D});

	// 시그니처 판별에 필요한 최대 앞부분 길이
	static final int MAX_SIGNATURE_LENGTH = 8;

	private final String mimeType;
	private final String extension;
	@Getter(AccessLevel.NONE)
	private final byte[] signature;

	/**
	 * "image/png; charset=..." 처럼 파라미터가 붙어도 본 형식만 비교한다.
	 */
	public static Optional<FileType> fromMimeType(String contentType) {
		if (contentType == null) {
			return Optional.empty();
		}
		int semicolon = contentType.indexOf(';');
		String mime = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
		return Arrays.stream(values()).filter(type -> type.mimeType.equals(mime)).findFirst();
	}

	public static Optional<FileType> fromSignature(byte[] header) {
		return Arrays.stream(values()).filter(type -> type.matchesSignature(header)).findFirst();
	}

	private boolean matchesSignature(byte[] header) {
		if (header.length < signature.length) {
			return false;
		}
		return Arrays.equals(header, 0, signature.length, signature, 0, signature.length);
	}
}
