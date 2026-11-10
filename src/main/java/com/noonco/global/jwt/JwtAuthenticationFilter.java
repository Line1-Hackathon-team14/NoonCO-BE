package com.noonco.global.jwt;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.noonco.global.ResultCode;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Authorization: Bearer {accessToken} 을 검증해 SecurityContext 에 principal = userId(Long) 를 채운다.
// 토큰이 없으면 그대로 통과시키고, 보호된 경로라면 SecurityConfig 의 EntryPoint 가 UNAUTHORIZED 로 응답한다.
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String TOKEN_PREFIX = "Bearer ";

	private final JwtTokenProvider jwtTokenProvider;
	private final RequestMatcher publicPaths;

	public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, RequestMatcher publicPaths) {
		this.jwtTokenProvider = jwtTokenProvider;
		this.publicPaths = publicPaths;
	}

	// 공개 경로는 만료·잘못된 토큰이 붙어 와도 막지 않도록 검증을 건너뛴다.
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return publicPaths.matches(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String header = request.getHeader("Authorization");

		if (header != null && header.startsWith(TOKEN_PREFIX)) {
			try {
				Long userId = jwtTokenProvider.getUserId(header.substring(TOKEN_PREFIX.length()));
				SecurityContextHolder.getContext().setAuthentication(
						new UsernamePasswordAuthenticationToken(userId, null, List.of()));
			} catch (ExpiredJwtException e) {
				SecurityErrorResponseWriter.write(response, ResultCode.ACCESS_TOKEN_EXPIRED);
				return;
			} catch (JwtException | IllegalArgumentException e) {
				SecurityErrorResponseWriter.write(response, ResultCode.UNAUTHORIZED);
				return;
			}
		}

		filterChain.doFilter(request, response);
	}
}
