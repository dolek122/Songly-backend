package com.songly.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String REQUEST_ID_HEADER = "X-Request-Id";

	public static final String REQUEST_ID_KEY = "requestId";
	public static final String USER_ID_KEY = "userId";

	private static final int MAX_INCOMING_LENGTH = 64;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String requestId = resolveRequestId(request);
		MDC.put(REQUEST_ID_KEY, requestId);
		response.setHeader(REQUEST_ID_HEADER, requestId);
		try {
			filterChain.doFilter(request, response);
		} finally {
			MDC.clear();
		}
	}

	private String resolveRequestId(HttpServletRequest request) {
		String incoming = request.getHeader(REQUEST_ID_HEADER);
		if (incoming == null || incoming.isBlank()) {
			return UUID.randomUUID().toString();
		}
		String sanitized = incoming.trim().replaceAll("[^A-Za-z0-9._-]", "");
		if (sanitized.isEmpty()) {
			return UUID.randomUUID().toString();
		}
		return sanitized.length() > MAX_INCOMING_LENGTH ? sanitized.substring(0, MAX_INCOMING_LENGTH) : sanitized;
	}
}
