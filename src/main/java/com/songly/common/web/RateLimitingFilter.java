package com.songly.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Fixed-window rate limiter oparty na Redis, chroni publiczne endpointy
 * (rejestracja, logowanie) przed botami zakładającymi konta / brute-force.
 * Uruchamia się przed uwierzytelnieniem, więc odpowiedź 429 pisze bezpośrednio
 * (nie przechodzi przez {@link GlobalExceptionHandler}).
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitingFilter extends OncePerRequestFilter {

	private final StringRedisTemplate redisTemplate;
	private final RateLimitProperties properties;
	private final ObjectMapper objectMapper;

	RateLimitingFilter(StringRedisTemplate redisTemplate, RateLimitProperties properties, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (!properties.isEnabled() || !properties.getProtectedPaths().contains(request.getRequestURI())) {
			filterChain.doFilter(request, response);
			return;
		}

		String key = "rate-limit:" + request.getRequestURI() + ":" + clientIp(request);
		Long count = redisTemplate.opsForValue().increment(key);
		if (count != null && count == 1L) {
			redisTemplate.expire(key, properties.getWindow());
		}
		if (count != null && count > properties.getLimit()) {
			writeTooManyRequests(response, request);
			return;
		}
		filterChain.doFilter(request, response);
	}

	private String clientIp(HttpServletRequest request) {
		String forwardedFor = request.getHeader("X-Forwarded-For");
		if (forwardedFor != null && !forwardedFor.isBlank()) {
			return forwardedFor.split(",")[0].trim();
		}
		return request.getRemoteAddr();
	}

	private void writeTooManyRequests(HttpServletResponse response, HttpServletRequest request) throws IOException {
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		ApiError error = new ApiError("RATE_LIMIT_EXCEEDED", "Zbyt wiele żądań, spróbuj ponownie później",
				Instant.now(), request.getRequestURI());
		objectMapper.writeValue(response.getWriter(), error);
	}
}
