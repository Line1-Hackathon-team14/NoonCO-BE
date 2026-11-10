package com.noonco.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.noonco.global.ResultCode;
import com.noonco.global.jwt.JwtAuthenticationFilter;
import com.noonco.global.jwt.JwtTokenProvider;
import com.noonco.global.jwt.SecurityErrorResponseWriter;

import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

	private static final String[] PERMIT_ALL_PATHS = {
			"/api/v1/auth/**",
			"/swagger-ui/**",
			"/swagger-ui.html",
			"/v3/api-docs/**"
	};

	private final JwtTokenProvider jwtTokenProvider;

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
				.csrf(csrf -> csrf.disable())
				.cors(Customizer.withDefaults())
				.httpBasic(httpBasic -> httpBasic.disable())
				.formLogin(formLogin -> formLogin.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
						.requestMatchers(PERMIT_ALL_PATHS).permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(exception -> exception
						.authenticationEntryPoint((request, response, authException) ->
								SecurityErrorResponseWriter.write(response, ResultCode.UNAUTHORIZED)))
				.addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class)
				.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
