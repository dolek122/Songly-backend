package com.songly.common.web;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 * jeszcze, kto pyta. Limit per użytkownik na ścieżkach za JWT to osobny
 * {@link UserRateLimitingFilter} (ADR 0034).
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitingFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

	/** W Prometheusie: {@code songly_rate_limit_rejected_total{path="..."}}. */
	private static final String REJECTED_METRIC = "songly.rate.limit.rejected";

	private final FixedWindowCounter counter;
	private final RateLimitProperties properties;
	private final ObjectMapper objectMapper;
	private final MeterRegistry meterRegistry;
	private final List<PathPattern> protectedPatterns;

	RateLimitingFilter(FixedWindowCounter counter, RateLimitProperties properties, ObjectMapper objectMapper,
			MeterRegistry meterRegistry) {
		this.counter = counter;
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
		Long count = counter.increment(key, properties.getWindow());
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
			TooManyRequestsResponse.write(response, request, counter.retryAfterSeconds(key, properties.getWindow()),
					objectMapper);
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
}
