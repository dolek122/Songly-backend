package com.songly.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitingFilterTest {

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	private final RateLimitProperties properties = new RateLimitProperties();

	/** Prawdziwy rejestr, nie mock — pozwala sprawdzić, ile faktycznie zliczył. */
	private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

	private RateLimitingFilter filterFor(List<String> protectedPaths) {
		properties.setProtectedPaths(protectedPaths);
		// Wzorce parsują się w konstruktorze, więc filtr musi powstać po ustawieniu
		// propercji — inaczej test sprawdzałby domyślną listę.
		return new RateLimitingFilter(redisTemplate, properties, new ObjectMapper(), meterRegistry);
	}

	private MockHttpServletResponse call(RateLimitingFilter filter, String uri) throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
		request.setRemoteAddr("10.0.0.1");
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		return response;
	}

	@Test
	void doesNotTouchRedis_whenPathIsNotProtected() throws Exception {
		RateLimitingFilter filter = filterFor(List.of("/api/v1/auth/login"));

		MockHttpServletResponse response = call(filter, "/api/v1/posts");

		assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
		verifyNoInteractions(redisTemplate);
	}

	@Test
	void doesNotTouchRedis_whenDisabled() throws Exception {
		RateLimitingFilter filter = filterFor(List.of("/api/v1/auth/login"));
		properties.setEnabled(false);

		call(filter, "/api/v1/auth/login");

		verifyNoInteractions(redisTemplate);
	}

	/**
	 * Wzorzec z gwiazdką musi dzielić jedno okno na wszystkie pasujące ścieżki.
	 * Licznik pod konkretnym URI dałby każdemu identyfikatorowi własny limit, czyli
	 * limit nie ograniczałby niczego.
	 */
	@Test
	void countsAllPathsMatchingOnePattern_inTheSameWindow() throws Exception {
		RateLimitingFilter filter = filterFor(List.of("/api/v1/things/*"));
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.increment(anyString())).thenReturn(1L, 2L);

		call(filter, "/api/v1/things/aaa");
		call(filter, "/api/v1/things/bbb");

		verify(valueOperations, times(2)).increment("rate-limit:/api/v1/things/*:10.0.0.1");
	}

	/**
	 * Redis to zabezpieczenie, nie warunek poprawności — jego awaria nie może
	 * odciąć wszystkim logowania. Wcześniej wyjątek leciał wyżej i kończył się
	 * 500-tką na ścieżce auth.
	 */
	@Test
	void letsRequestThrough_whenRedisIsDown() throws Exception {
		RateLimitingFilter filter = filterFor(List.of("/api/v1/auth/login"));
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.increment(anyString())).thenThrow(new IllegalStateException("Redis nie odpowiada"));

		MockHttpServletResponse response = call(filter, "/api/v1/auth/login");

		assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
	}

	@Test
	void reportsRetryAfterFromRemainingWindow_whenLimitExceeded() throws Exception {
		RateLimitingFilter filter = filterFor(List.of("/api/v1/auth/login"));
		properties.setLimit(2);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.increment(anyString())).thenReturn(3L);
		when(redisTemplate.getExpire(anyString(), any(TimeUnit.class))).thenReturn(37L);

		MockHttpServletResponse response = call(filter, "/api/v1/auth/login");

		assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
		assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("37");
		assertThat(response.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
		// Trafienie w limit musi być widoczne w metrykach — inaczej atak na publiczny
		// endpoint przechodzi bez śladu na dashboardzie.
		assertThat(meterRegistry.counter("songly.rate.limit.rejected", "path", "/api/v1/auth/login").count())
				.isEqualTo(1);
	}

	@Test
	void fallsBackToFullWindow_whenTtlIsUnknown() throws Exception {
		RateLimitingFilter filter = filterFor(List.of("/api/v1/auth/login"));
		properties.setLimit(1);
		properties.setWindow(Duration.ofMinutes(2));
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.increment(anyString())).thenReturn(9L);
		when(redisTemplate.getExpire(anyString(), any(TimeUnit.class))).thenReturn(-1L);

		MockHttpServletResponse response = call(filter, "/api/v1/auth/login");

		assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("120");
	}
}
