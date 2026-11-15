package com.noonco.global;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ResultCode {

	OK(HttpStatus.OK, "요청이 성공했습니다."),
	CREATED(HttpStatus.CREATED, "생성되었습니다."),

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
	ACCESS_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다."),
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
	INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 자료를 찾을 수 없습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
	EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
	ANALYSIS_IN_PROGRESS(HttpStatus.CONFLICT, "이미 해설을 만들고 있습니다. 잠시 후 다시 시도해 주세요."),
	ANALYSIS_ALREADY_COMPLETED(HttpStatus.CONFLICT, "이미 해설이 완료된 자료입니다."),
	FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "파일 크기는 5MB 이하여야 합니다."),
	INVALID_FILE_TYPE(HttpStatus.BAD_REQUEST, "지원하지 않는 파일 형식입니다. 파일을 확인해 주세요."),
	PDF_UNREADABLE(HttpStatus.UNPROCESSABLE_CONTENT, "PDF 파일을 열 수 없습니다. 암호가 걸려 있거나 손상된 파일인지 확인해 주세요."),
	PDF_TOO_MANY_PAGES(HttpStatus.UNPROCESSABLE_CONTENT, "PDF는 30쪽 이하만 올릴 수 있습니다."),
	AI_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "지금은 해설 요청이 많습니다. 잠시 후 다시 시도해 주세요."),
	// 아래 셋은 해설 스트림 error 이벤트로만 보낸다 (HTTP 상태는 이미 200 으로 나간 뒤라 쓰이지 않는다)
	UNREADABLE_IMAGE(HttpStatus.UNPROCESSABLE_CONTENT, "사진을 읽지 못했어요. 다시 촬영해 주세요."),
	AI_FAILED(HttpStatus.BAD_GATEWAY, "해설을 만들지 못했어요. 잠시 후 다시 시도해 주세요."),
	AI_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "해설 생성이 오래 걸려 중단했어요. 다시 시도해 주세요."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");

	private final HttpStatus status;
	private final String message;
}
