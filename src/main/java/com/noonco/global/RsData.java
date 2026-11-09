package com.noonco.global;

public record RsData<T>(String resultCode, String msg, T data) {

	public static <T> RsData<T> success(T data) {
		return of(ResultCode.OK, data);
	}

	public static <T> RsData<T> success(ResultCode resultCode, T data) {
		return of(resultCode, data);
	}

	public static RsData<Void> fail(ResultCode resultCode) {
		return of(resultCode, null);
	}

	public static <T> RsData<T> fail(ResultCode resultCode, T data) {
		return of(resultCode, data);
	}

	private static <T> RsData<T> of(ResultCode resultCode, T data) {
		return new RsData<>(resultCode.name(), resultCode.getMessage(), data);
	}
}
