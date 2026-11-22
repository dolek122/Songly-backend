package com.songly.common.web;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.server.PathContainer;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;

@Component
@EnableConfigurationProperties(UserRateLimitProperties.class)
public class UserRateLimitingFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(UserRateLimitingFilter.class);

	private static final String REJECTED_METRIC = "songly.rate.limit.user.rejected";

	private final FixedWindowCounter counter;
	private final UserRateLimitProperties properties;
	private final ObjectMapper objectMapper;
	private final MeterRegistry meterRegistry;
	private final List<CompiledRule> rules;

	UserRateLimitingFilter(FixedWindowCounter counter, UserRateLimitProperties properties, ObjectMapper objectMapper,
			MeterRegistry meterRegistry) {
		this.counter = counter;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.meterRegistry = meterRegistry;
		PathPatternParser parser = new PathPatternParser();
		this.rules = properties.getRules().stream()
				.map(rule -> new CompiledRule(rule,
						rule.getMethods().stream().map(method -> method.toUpperCase(Locale.ROOT))
								.collect(Collectors.toSet()),
						rule.getPaths().stream().map(parser::parse).toList()))
				.toList();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String userId = properties.isEnabled() ? authenticatedUserId() : null;
		if (userId == null) {
			filterChain.doFilter(request, response);
			return;
		}

		PathContainer path = PathContainer.parsePath(request.getRequestURI());
		long retryAfter = 0;
		String rejectedBy = null;
		for (CompiledRule rule : rules) {
			if (!rule.matches(request.getMethod(), path)) {
				continue;
			}
			String key = "rate-limit:user:" + rule.definition().getName() + ":" + userId;
			Long count = counter.increment(key, rule.definition().getWindow());
			if (count != null && count > rule.definition().getLimit()) {
				rejectedBy = rejectedBy == null ? rule.definition().getName() : rejectedBy;
				retryAfter = Math.max(retryAfter, counter.retryAfterSeconds(key, rule.definition().getWindow()));
			}
		}

		if (rejectedBy != null) {
			meterRegistry.counter(REJECTED_METRIC, "rule", rejectedBy).increment();
			log.warn("Limit zapisów użytkownika przekroczony: {} {} (reguła {})", request.getMethod(),
					request.getRequestURI(), rejectedBy);
			TooManyRequestsResponse.write(response, request, retryAfter, objectMapper);
			return;
		}
		filterChain.doFilter(request, response);
	}

	private static String authenticatedUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken) {
			return null;
		}
		return authentication.getName();
	}

	private record CompiledRule(UserRateLimitProperties.Rule definition, Set<String> methods, List<PathPattern> paths) {

		boolean matches(String method, PathContainer path) {
			return methods.contains(method) && paths.stream().anyMatch(pattern -> pattern.matches(path));
		}
	}
}
