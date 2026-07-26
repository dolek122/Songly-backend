package com.songly.identity.security;

import com.songly.common.web.RequestIdFilter;
import com.songly.identity.AdminStatusService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtService jwtService;
	private final AdminStatusService adminStatusService;

	JwtAuthenticationFilter(JwtService jwtService, AdminStatusService adminStatusService) {
		this.jwtService = jwtService;
		this.adminStatusService = adminStatusService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.startsWith(BEARER_PREFIX)) {
			try {
				UUID userId = jwtService.parseUserId(header.substring(BEARER_PREFIX.length()));
				boolean admin = adminStatusService.isAdmin(userId);
				List<GrantedAuthority> authorities = admin
						? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
						: List.of();
				SecurityContextHolder.getContext()
						.setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, authorities));
				// Sprzątanie MDC należy do RequestIdFilter, który zamyka cały łańcuch — tu
				// tylko dokładamy to, czego przed uwierzytelnieniem nie było jak poznać.
				MDC.put(RequestIdFilter.USER_ID_KEY, userId.toString());
				if (admin) {
					// Adminów jest niewielu, a ich żądania sięgają danych całego serwisu —
					// ślad audytowy każdego z nich jest tańszy niż odtwarzanie go po fakcie.
					log.info("Żądanie z uprawnieniami administratora: {} {}", request.getMethod(),
							request.getRequestURI());
				}
			} catch (RuntimeException e) {
				// Wygasły albo podrobiony token — normalny ruch, nie awaria. Bez treści
				// wyjątku, bo niesie fragmenty tokenu.
				SecurityContextHolder.clearContext();
				log.debug("Odrzucono token dla {} {}", request.getMethod(), request.getRequestURI());
			}
		}
		filterChain.doFilter(request, response);
	}
}
