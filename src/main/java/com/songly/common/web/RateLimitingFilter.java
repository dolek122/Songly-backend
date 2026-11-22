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

@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitingFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

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

		String key = "rate-limit:" + pattern + ":" + clientIp(request);
		Long count = counter.increment(key, properties.getWindow());
		if (count == null) {
			filterChain.doFilter(request, response);
			return;
		}
		if (count > properties.getLimit()) {
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

	private String clientIp(HttpServletRequest request) {
		return request.getRemoteAddr();
	}
}
