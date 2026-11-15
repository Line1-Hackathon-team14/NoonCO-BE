package com.noonco.global.jwt;

import java.io.IOException;

import org.springframework.http.MediaType;

import com.noonco.global.ResultCode;

import jakarta.servlet.http.HttpServletResponse;

// 필터 체인은 GlobalExceptionHandler 를 거치지 않으므로 RsData 와 같은 형식의 응답을 직접 작성한다.
public final class SecurityErrorResponseWriter {

	private SecurityErrorResponseWriter() {
	}

	public static void write(HttpServletResponse response, ResultCode resultCode) throws IOException {
		response.setStatus(resultCode.getStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("""
				{"resultCode":"%s","msg":"%s","data":null}""".formatted(resultCode.name(), resultCode.getMessage()));
	}
}
