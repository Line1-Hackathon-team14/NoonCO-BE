package com.noonco.auth.dto;

public record TokenResponse(String accessToken, String refreshToken) {
}
