package com.songly.identity.security;

import com.songly.common.web.RateLimitingFilter;
import com.songly.common.web.UserRateLimitingFilter;
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
	private final UserRateLimitingFilter userRateLimitingFilter;
	private final RestAuthenticationEntryPoint authenticationEntryPoint;
	private final RestAccessDeniedHandler accessDeniedHandler;

	SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, RateLimitingFilter rateLimitingFilter,
			UserRateLimitingFilter userRateLimitingFilter, RestAuthenticationEntryPoint authenticationEntryPoint,
			RestAccessDeniedHandler accessDeniedHandler) {
		this.jwtAuthenticationFilter = jwtAuthenticationFilter;
		this.rateLimitingFilter = rateLimitingFilter;
		this.userRateLimitingFilter = userRateLimitingFilter;
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
						// Cały prefiks, nie pojedyncze metody: kolejny endpoint admina jest
						// wtedy chroniony domyślnie, a nie dopiero gdy ktoś dopisze regułę
						// (ADR 0021).
						.requestMatchers("/api/v1/admin/**").hasAuthority("ROLE_ADMIN")
						// Co wolno ZAWIESZONEMU koncie (ADR 0022). Lista jest celowo krótka i
						// zamknięta: poznać swój stan, wylogować się i usunąć konto. To
						// ostatnie jest wymogiem RODO (§9) — zawieszenie nie może odcinać od
						// prawa do usunięcia danych, więc twardy blok logowania był
						// nieakceptowalny.
						.requestMatchers(HttpMethod.GET, "/api/v1/auth/me/suspension")
						.hasAnyAuthority(JwtAuthenticationFilter.USER_AUTHORITY,
								JwtAuthenticationFilter.SUSPENDED_AUTHORITY)
						.requestMatchers(HttpMethod.DELETE, "/api/v1/auth/me")
						.hasAnyAuthority(JwtAuthenticationFilter.USER_AUTHORITY,
								JwtAuthenticationFilter.SUSPENDED_AUTHORITY)
						// Odwołanie od zawieszenia (ADR 0023) — bez tego wpisu zawieszony nie
						// miałby jak zakwestionować decyzji, a sam fakt jej poznania byłby
						// tylko lepiej opisaną ścianą.
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/me/suspension/appeal")
						.hasAnyAuthority(JwtAuthenticationFilter.USER_AUTHORITY,
								JwtAuthenticationFilter.SUSPENDED_AUTHORITY)
						// Reszta wymaga PEŁNYCH uprawnień konta, nie samego uwierzytelnienia:
						// dzięki temu każdy nowy endpoint jest domyślnie zamknięty dla
						// zawieszonego, zamiast wymagać dopisania sprawdzenia w kodzie.
						.anyRequest().hasAuthority(JwtAuthenticationFilter.USER_AUTHORITY))
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterBefore(rateLimitingFilter, JwtAuthenticationFilter.class)
				// Za filtrem JWT, bo liczy per użytkownik, a przed autoryzacją — odrzucone
				// żądanie nie dotyka kontrolera (ADR 0034).
				.addFilterAfter(userRateLimitingFilter, JwtAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
