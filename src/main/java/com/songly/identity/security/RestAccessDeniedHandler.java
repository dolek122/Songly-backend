package com.songly.identity.security;

import com.songly.common.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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

	/**
	 * Zawieszone konto dostaje <b>własny kod</b>, nie ogólne {@code FORBIDDEN} (ADR
	 * 0022). Inaczej klient nie ma jak odróżnić „nie masz dostępu do tego zasobu"
	 * od „twoje konto jest zawieszone", a zawieszony widziałby aplikację, w której
	 * losowo wszystko przestało działać — czyli decyzję wyglądającą jak awaria,
	 * dokładnie to, od czego zaczął się ADR 0012.
	 */
	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
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
