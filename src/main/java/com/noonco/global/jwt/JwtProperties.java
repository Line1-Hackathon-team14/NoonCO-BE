package com.noonco.global.jwt;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("jwt")
public record JwtProperties(
		String secret,
		@DefaultValue("30m") Duration accessTokenExpiration,
		@DefaultValue("14d") Duration refreshTokenExpiration
) {
}
