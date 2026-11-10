package com.noonco.global.jwt;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

// Access Token 발급과 검증을 담당한다.
// 파싱 중 발생하는 ExpiredJwtException / JwtException 은 그대로 던지고 JwtAuthenticationFilter 가 401 로 변환한다.
@Component
@EnableConfigurationProperties(JwtProperties.class)
public class JwtTokenProvider {

	private final SecretKey key;
	private final long accessTokenExpirationMs;

	public JwtTokenProvider(JwtProperties jwtProperties) {
		this.key = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
		this.accessTokenExpirationMs = jwtProperties.accessTokenExpiration().toMillis();
	}

	public String createAccessToken(Long userId) {
		Date now = new Date();
		return Jwts.builder()
				.subject(String.valueOf(userId))
				.issuedAt(now)
				.expiration(new Date(now.getTime() + accessTokenExpirationMs))
				.signWith(key)
				.compact();
	}

	public Long getUserId(String token) {
		String subject = Jwts.parser()
				.verifyWith(key)
				.build()
				.parseSignedClaims(token)
				.getPayload()
				.getSubject();
		return Long.valueOf(subject);
	}
}
