package com.noonco.global.exception;

import com.noonco.global.ResultCode;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

	private final ResultCode resultCode;

	public BusinessException(ResultCode resultCode) {
		super(resultCode.getMessage());
		this.resultCode = resultCode;
	}
}
