package com.songly.identity.security;

import com.songly.identity.AdminStatusService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class JwtAuthenticationFilter extends OncePerRequestFilter {

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
				List<GrantedAuthority> authorities = adminStatusService.isAdmin(userId)
						? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
						: List.of();
				SecurityContextHolder.getContext()
						.setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, authorities));
			} catch (RuntimeException e) {
				SecurityContextHolder.clearContext();
			}
		}
		filterChain.doFilter(request, response);
	}
}
