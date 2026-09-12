package com.songly.identity.security;

import com.songly.common.web.RequestIdFilter;
import com.songly.identity.AccountStatus;
import com.songly.identity.AccountStatusService;
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

	/**
	 * Zwykłe, pełne uprawnienia konta — wszystko poza listą wyjątków ich wymaga.
	 */
	static final String USER_AUTHORITY = "ROLE_USER";

	/** Konto zawieszone (ADR 0022) — zamiast {@link #USER_AUTHORITY}, nie obok. */
	static final String SUSPENDED_AUTHORITY = "ROLE_SUSPENDED";

	private final JwtService jwtService;
	private final AccountStatusService accountStatusService;

	JwtAuthenticationFilter(JwtService jwtService, AccountStatusService accountStatusService) {
		this.jwtService = jwtService;
		this.accountStatusService = accountStatusService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.startsWith(BEARER_PREFIX)) {
			try {
				UUID userId = jwtService.parseUserId(header.substring(BEARER_PREFIX.length()));
				AccountStatus status = accountStatusService.statusOf(userId);
				boolean admin = status.admin();
				// Zawieszone konto dostaje WYŁĄCZNIE ROLE_SUSPENDED, nigdy ROLE_USER (ADR
				// 0022). Uprawnienia są tu całą egzekucją zawieszenia: wszystko poza krótką
				// listą z SecurityConfig wymaga ROLE_USER, więc nowy endpoint jest domyślnie
				// niedostępny dla zawieszonego — i nie da się o tym zapomnieć tak, jak dałoby
				// się zapomnieć o sprawdzeniu w każdej ścieżce zapisu osobno.
				List<GrantedAuthority> authorities = status.suspended()
						? List.of(new SimpleGrantedAuthority(SUSPENDED_AUTHORITY))
						: admin
								? List.of(new SimpleGrantedAuthority(USER_AUTHORITY),
										new SimpleGrantedAuthority("ROLE_ADMIN"))
								: List.of(new SimpleGrantedAuthority(USER_AUTHORITY));
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
