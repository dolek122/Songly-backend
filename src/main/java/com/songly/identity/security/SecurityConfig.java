package com.songly.identity.security;

import com.songly.common.web.RateLimitingFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
class SecurityConfig {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;
	private final RateLimitingFilter rateLimitingFilter;
	private final RestAuthenticationEntryPoint authenticationEntryPoint;
	private final RestAccessDeniedHandler accessDeniedHandler;

	SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, RateLimitingFilter rateLimitingFilter,
			RestAuthenticationEntryPoint authenticationEntryPoint, RestAccessDeniedHandler accessDeniedHandler) {
		this.jwtAuthenticationFilter = jwtAuthenticationFilter;
		this.rateLimitingFilter = rateLimitingFilter;
		this.authenticationEntryPoint = authenticationEntryPoint;
		this.accessDeniedHandler = accessDeniedHandler;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http.csrf(csrf -> csrf.disable())
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(eh -> eh.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/api/v1/auth/register", "/api/v1/auth/register/verify", "/api/v1/auth/login",
								"/api/v1/auth/refresh", "/api/v1/auth/logout")
						.permitAll()
						// Reset hasła jest z definicji dla kogoś, kto nie może się zalogować.
						// To osobne ścieżki od PUT /api/v1/auth/password (zmiana hasła przy
						// znanym obecnym haśle), która zostaje za uwierzytelnieniem.
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/password/reset-request",
								"/api/v1/auth/password/reset")
						.permitAll().requestMatchers("/api/v1/now-playing/spotify/callback").permitAll()
						.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
						.requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
						.requestMatchers("/actuator/prometheus", "/actuator/metrics/**").hasAuthority("ROLE_ADMIN")
						.requestMatchers(HttpMethod.GET, "/api/v1/analytics/summary").hasAuthority("ROLE_ADMIN")
						.anyRequest().authenticated())
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterBefore(rateLimitingFilter, JwtAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
