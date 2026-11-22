package com.songly.identity.security;

import com.songly.common.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		ApiError error = suspended()
				? new ApiError("ACCOUNT_SUSPENDED",
						"Konto jest zawieszone — szczegóły pod GET /api/v1/auth/me/suspension", Instant.now(),
						request.getRequestURI())
				: new ApiError("FORBIDDEN", "Brak uprawnień do tego zasobu", Instant.now(), request.getRequestURI());
		objectMapper.writeValue(response.getWriter(), error);
	}

	private boolean suspended() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication != null && authentication.getAuthorities().stream()
				.anyMatch(authority -> JwtAuthenticationFilter.SUSPENDED_AUTHORITY.equals(authority.getAuthority()));
	}
}
