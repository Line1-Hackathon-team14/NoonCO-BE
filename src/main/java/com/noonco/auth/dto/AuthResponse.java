package com.noonco.auth.dto;

import com.noonco.user.dto.UserResponse;

public record AuthResponse(String accessToken, String refreshToken, UserResponse user) {
}
