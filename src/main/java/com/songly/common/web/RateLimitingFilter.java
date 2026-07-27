package com.songly.common.web;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;

/**
 * Fixed-window rate limiter oparty na Redis, chroni publiczne endpointy przed
 * botami zakładającymi konta i brute-force'em (Claude.md §9). Uruchamia się
 * przed uwierzytelnieniem, więc odpowiedź 429 pisze bezpośrednio (nie
 * przechodzi przez {@link GlobalExceptionHandler}) — i z tego samego powodu
 * limituje po IP, a nie po użytkowniku: na tym etapie łańcucha nie wiadomo
 * jeszcze, kto pyta.
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitingFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

	/** W Prometheusie: {@code songly_rate_limit_rejected_total{path="..."}}. */
	private static final String REJECTED_METRIC = "songly.rate.limit.rejected";

	private final StringRedisTemplate redisTemplate;
	private final RateLimitProperties properties;
	private final ObjectMapper objectMapper;
	private final MeterRegistry meterRegistry;
	private final List<PathPattern> protectedPatterns;

	RateLimitingFilter(StringRedisTemplate redisTemplate, RateLimitProperties properties, ObjectMapper objectMapper,
			MeterRegistry meterRegistry) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.meterRegistry = meterRegistry;
		PathPatternParser parser = new PathPatternParser();
		this.protectedPatterns = properties.getProtectedPaths().stream().map(parser::parse).toList();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String pattern = properties.isEnabled() ? matchedPattern(request) : null;
		if (pattern == null) {
			filterChain.doFilter(request, response);
			return;
		}

		// Licznik pod wzorcem, nie pod konkretnym URI: inaczej wzorzec z gwiazdką
		// dawałby każdej ścieżce własne okno i limit nie limitowałby niczego.
		String key = "rate-limit:" + pattern + ":" + clientIp(request);
		Long count = increment(key);
		if (count == null) {
			filterChain.doFilter(request, response);
			return;
		}
		if (count > properties.getLimit()) {
			// Odrzucenie to sygnał, że ktoś dobija się do publicznego endpointu — bez logu
			// i licznika trafienie w limit było dotąd całkowicie niewidoczne z zewnątrz.
			// Wzorzec ścieżki, nie URI: inaczej tag metryki miałby nieograniczoną
			// liczbę wartości (kardynalność), co rozsadza magazyn Prometheusa.
			meterRegistry.counter(REJECTED_METRIC, "path", pattern).increment();
			log.warn("Limit żądań przekroczony dla {} (wzorzec {})", request.getRequestURI(), pattern);
			writeTooManyRequests(response, request, key);
			return;
		}
		filterChain.doFilter(request, response);
	}

	private String matchedPattern(HttpServletRequest request) {
		PathContainer path = PathContainer.parsePath(request.getRequestURI());
		return protectedPatterns.stream().filter(pattern -> pattern.matches(path)).findFirst()
				.map(PathPattern::getPatternString).orElse(null);
	}

	/**
	 * Gdy Redis nie odpowiada, przepuszczamy żądanie ({@code null}) zamiast zwracać
	 * błąd. Rate limiting jest zabezpieczeniem, nie warunkiem poprawności — awaria
	 * cache'a nie może odciąć wszystkim logowania i rejestracji. Wcześniej wyjątek
	 * leciał wyżej i logowanie kończyło się 500-tką.
	 */
	private Long increment(String key) {
		try {
			Long count = redisTemplate.opsForValue().increment(key);
			if (count != null && count == 1L) {
				redisTemplate.expire(key, properties.getWindow());
			}
			return count;
		} catch (RuntimeException ex) {
			log.warn("Rate limiting wyłączone dla tego żądania — Redis nie odpowiada: {}", ex.toString());
			return null;
		}
	}

	/**
	 * Prawdziwe IP klienta. Za proxy (Render) daje je dopiero
	 * {@code server.forward-headers-strategy}, które każe Springowi przepisać
	 * {@code X-Forwarded-For} na {@code getRemoteAddr}. Celowo nie czytamy tego
	 * nagłówka sami: bez wiedzy, czy przed nami stoi zaufane proxy, jest on zwykłym
	 * polem od klienta — a więc darmowym obejściem limitu przez podstawianie
	 * losowych adresów.
	 */
	private String clientIp(HttpServletRequest request) {
		return request.getRemoteAddr();
	}

	private void writeTooManyRequests(HttpServletResponse response, HttpServletRequest request, String key)
			throws IOException {
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds(key)));
		ApiError error = new ApiError("RATE_LIMIT_EXCEEDED", "Zbyt wiele żądań, spróbuj ponownie później",
				Instant.now(), request.getRequestURI());
		objectMapper.writeValue(response.getWriter(), error);
	}

	/**
	 * Ile realnie zostało do końca okna — klient nie musi zgadywać ani dobijać się
	 * co sekundę. Gdy TTL jest niedostępny, podajemy pełne okno: lepiej kazać
	 * czekać za długo niż za krótko.
	 */
	private long retryAfterSeconds(String key) {
		try {
			Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
			if (ttl != null && ttl > 0) {
				return ttl;
			}
		} catch (RuntimeException ex) {
			log.warn("Nie udało się odczytać TTL okna limitu: {}", ex.toString());
		}
		return Math.max(1, properties.getWindow().toSeconds());
	}
}
