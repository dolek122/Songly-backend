package com.songly.identity.security;

import com.songly.common.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class RestAccessDeniedHandler implements AccessDeniedHandler {

	private final ObjectMapper objectMapper;

	RestAccessDeniedHandler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		ApiError error = new ApiError("FORBIDDEN", "Brak uprawnień do tego zasobu", Instant.now(),
				request.getRequestURI());
		objectMapper.writeValue(response.getWriter(), error);
	}
}
