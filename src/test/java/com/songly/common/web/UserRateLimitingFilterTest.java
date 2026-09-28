package com.songly.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserRateLimitingFilterTest {

	private static final String USER_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	private final UserRateLimitProperties properties = new UserRateLimitProperties();

	private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	private UserRateLimitingFilter filterWith(UserRateLimitProperties.Rule... rules) {
		properties.setRules(List.of(rules));
		// Reguły kompilują się w konstruktorze, więc filtr musi powstać po ustawieniu
		// propercji — inaczej test sprawdzałby domyślne reguły.
		return new UserRateLimitingFilter(new FixedWindowCounter(redisTemplate), properties, new ObjectMapper(),
				meterRegistry);
	}

	private static UserRateLimitProperties.Rule rule(String name, List<String> methods, String path, int limit,
			Duration window) {
		return new UserRateLimitProperties.Rule(name, methods, List.of(path), limit, window);
	}

	private static void authenticate() {
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(USER_ID, null,
				AuthorityUtils.createAuthorityList("ROLE_USER")));
	}

	private MockHttpServletResponse call(UserRateLimitingFilter filter, String method, String uri) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(new MockHttpServletRequest(method, uri), response, new MockFilterChain());
		return response;
	}

	@Test
	void doesNotTouchRedis_whenRequestIsNotAuthenticated() throws Exception {
		UserRateLimitingFilter filter = filterWith(
				rule("writes", List.of("POST"), "/api/**", 1, Duration.ofMinutes(1)));

		MockHttpServletResponse response = call(filter, "POST", "/api/v1/posts");

		assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
		verifyNoInteractions(redisTemplate);
	}

	@Test
	void doesNotTouchRedis_whenDisabled() throws Exception {
		UserRateLimitingFilter filter = filterWith(
				rule("writes", List.of("POST"), "/api/**", 1, Duration.ofMinutes(1)));
		properties.setEnabled(false);
		authenticate();

		call(filter, "POST", "/api/v1/posts");

		verifyNoInteractions(redisTemplate);
	}

	/** Metoda spoza reguły — tu odczyt — nie jest liczona, choć ścieżka pasuje. */
	@Test
	void doesNotCount_whenMethodIsNotInRule() throws Exception {
		UserRateLimitingFilter filter = filterWith(
				rule("writes", List.of("POST", "PUT"), "/api/**", 1, Duration.ofMinutes(1)));
		authenticate();

		call(filter, "GET", "/api/v1/posts");

		verifyNoInteractions(redisTemplate);
	}

	/**
	 * Klucz jest nazwą reguły i użytkownikiem, nie ścieżką — cała grupa dzieli
	 * jeden licznik.
	 */
	@Test
	void countsUnderRuleNameAndUser() throws Exception {
		UserRateLimitingFilter filter = filterWith(
				rule("interactions", List.of("PUT", "POST"), "/api/v1/posts/*/likes", 5, Duration.ofMinutes(10)));
		authenticate();
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.increment(anyString())).thenReturn(1L);

		call(filter, "PUT", "/api/v1/posts/abc/likes");

		verify(valueOperations).increment("rate-limit:user:interactions:" + USER_ID);
		verify(redisTemplate).expire("rate-limit:user:interactions:" + USER_ID, Duration.ofMinutes(10));
	}

	@Test
	void letsRequestThrough_whenRedisIsDown() throws Exception {
		UserRateLimitingFilter filter = filterWith(
				rule("writes", List.of("POST"), "/api/**", 1, Duration.ofMinutes(1)));
		authenticate();
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.increment(anyString())).thenThrow(new IllegalStateException("Redis nie odpowiada"));

		MockHttpServletResponse response = call(filter, "POST", "/api/v1/posts");

		assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
	}

	/**
	 * Przy dwóch przekroczonych regułach Retry-After musi wskazywać późniejszą —
	 * krótszy obiecywałby powrót kończący się kolejnym 429.
	 */
	@Test
	void reportsLatestRetryAfter_whenSeveralRulesAreExceeded() throws Exception {
		UserRateLimitingFilter filter = filterWith(rule("writes", List.of("PUT"), "/api/**", 1, Duration.ofMinutes(1)),
				rule("interactions", List.of("PUT"), "/api/v1/posts/*/likes", 1, Duration.ofMinutes(10)));
		authenticate();
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.increment(anyString())).thenReturn(2L);
		when(redisTemplate.getExpire(eq("rate-limit:user:writes:" + USER_ID), eq(TimeUnit.SECONDS))).thenReturn(20L);
		when(redisTemplate.getExpire(eq("rate-limit:user:interactions:" + USER_ID), eq(TimeUnit.SECONDS)))
				.thenReturn(480L);

		MockHttpServletResponse response = call(filter, "PUT", "/api/v1/posts/abc/likes");

		assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
		assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("480");
		assertThat(response.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
		// Tag to nazwa reguły z konfiguracji — ograniczona liczba wartości (§13).
		assertThat(meterRegistry.counter("songly.rate.limit.user.rejected", "rule", "writes").count()).isEqualTo(1);
	}
}
