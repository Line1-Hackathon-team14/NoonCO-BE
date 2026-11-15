package com.noonco.global.exception;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.noonco.global.ResultCode;
import com.noonco.global.RsData;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final String BINDING_FAILURE_MESSAGE = "입력 형식이 올바르지 않습니다.";

	public record FieldErrorData(String field, String message) {
	}

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<RsData<Void>> handleBusinessException(BusinessException e) {
		ResultCode resultCode = e.getResultCode();
		return ResponseEntity.status(resultCode.getStatus()).contentType(MediaType.APPLICATION_JSON).body(RsData.fail(resultCode));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<RsData<Void>> handleUnexpectedException(Exception e) {
		log.error("Unhandled exception", e);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON).body(RsData.fail(ResultCode.INTERNAL_ERROR));
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldErrorData> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> new FieldErrorData(error.getField(),
						error.isBindingFailure() ? BINDING_FAILURE_MESSAGE : error.getDefaultMessage()))
				.toList();
		return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_JSON)
				.body(RsData.fail(ResultCode.VALIDATION_FAILED, fieldErrors));
	}

	@Override
	protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return ResponseEntity.status(ResultCode.FILE_TOO_LARGE.getStatus()).headers(headers).contentType(MediaType.APPLICATION_JSON)
				.body(RsData.fail(ResultCode.FILE_TOO_LARGE));
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		if (statusCode.is5xxServerError()) {
			log.error("Spring MVC exception", ex);
		}
		HttpHeaders jsonHeaders = new HttpHeaders();
		jsonHeaders.addAll(headers);
		jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
		return super.handleExceptionInternal(ex, RsData.fail(resolveResultCode(statusCode)), jsonHeaders, statusCode, request);
	}

	private ResultCode resolveResultCode(HttpStatusCode statusCode) {
		if (statusCode.isSameCodeAs(HttpStatus.NOT_FOUND)) {
			return ResultCode.RESOURCE_NOT_FOUND;
		}
		if (statusCode.isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) {
			return ResultCode.METHOD_NOT_ALLOWED;
		}
		return statusCode.is4xxClientError() ? ResultCode.INVALID_REQUEST : ResultCode.INTERNAL_ERROR;
	}
}
