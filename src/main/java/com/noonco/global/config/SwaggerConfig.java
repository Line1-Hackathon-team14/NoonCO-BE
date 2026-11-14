package com.noonco.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

	@Bean
	public OpenAPI openAPI() {
		return new OpenAPI().info(new Info()
				.title("NoonCO API")
				.version("v1")
				.description("""
						토큰 오류는 모든 보호 API 에서 공통으로 401 로 응답한다.
						- UNAUTHORIZED: 토큰이 없거나 잘못됨 → 다시 로그인
						- ACCESS_TOKEN_EXPIRED: Access Token 만료 → POST /api/v1/auth/refresh 로 재발급 후 다시 요청"""));
	}
}
