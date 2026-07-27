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

/**
 * Nadaje każdemu żądaniu identyfikator i wkłada go do MDC, skąd trafia do
 * każdej linii logu (Claude.md §13).
 *
 * <p>
 * Bez tego structured logging jest tylko ładniej sformatowanym szumem: przy
 * kilku żądaniach naraz nie da się powiedzieć, które linie należą do tego
 * samego, więc diagnostyka sprowadza się do zgadywania po timestampach.
 * Identyfikator wraca też w nagłówku odpowiedzi — użytkownik zgłaszający błąd
 * podaje wtedy klucz, którym znajdujemy dokładnie jego żądanie.
 *
 * <p>
 * Filtr jest pierwszy w łańcuchu ({@code HIGHEST_PRECEDENCE}), żeby odrzucenia
 * z rate limitera i nieudane uwierzytelnienia też miały identyfikator — to
 * właśnie te żądania najczęściej się bada.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String REQUEST_ID_HEADER = "X-Request-Id";

	/** Klucze MDC; w logach ECS wychodzą jako pola najwyższego poziomu. */
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
			// Wątki są z puli i wracają do obsługi kolejnych żądań — bez tego czyszczenia
			// następne żądanie logowałoby się pod cudzym identyfikatorem.
			MDC.clear();
		}
	}

	/**
	 * Przychodzący identyfikator honorujemy, żeby dało się połączyć logi z klientem
	 * mobilnym albo proxy, ale przycinamy i sanityzujemy: to wartość spod kontroli
	 * klienta, a ląduje w każdej linii logu. Bez limitu długości i filtra znaków
	 * dałoby się wstrzykiwać do logów dowolną treść.
	 */
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
