package com.songly.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/**
 * Odpowiedź 429 pisana wprost przez filtry limitujące. Filtry działają przed
 * {@code DispatcherServletem}, więc nie przechodzą przez
 * {@link GlobalExceptionHandler} — format błędu (§6) muszą odtworzyć same, i
 * robią to tutaj, żeby oba limitery odpowiadały identycznie.
 */
final class TooManyRequestsResponse {

	static final String CODE = "RATE_LIMIT_EXCEEDED";

	private TooManyRequestsResponse() {
	}

	static void write(HttpServletResponse response, HttpServletRequest request, long retryAfterSeconds,
			ObjectMapper objectMapper) throws IOException {
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
		ApiError error = new ApiError(CODE, "Zbyt wiele żądań, spróbuj ponownie później", Instant.now(),
				request.getRequestURI());
		objectMapper.writeValue(response.getWriter(), error);
	}
}
