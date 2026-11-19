package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Za dużo prób logowania na jeden adres (ADR 0051). Komunikat mówi o adresie,
 * nie o koncie, bo limit działa tak samo dla adresów bez konta: inaczej sama
 * odmowa zdradzałaby, które adresy są zarejestrowane.
 */
class LoginRateLimitExceededException extends ApiException {

	private final long retryAfterSeconds;

	LoginRateLimitExceededException(long retryAfterSeconds) {
		super(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMIT_EXCEEDED",
				"Zbyt wiele prób logowania na ten adres, spróbuj ponownie później");
		this.retryAfterSeconds = retryAfterSeconds;
	}

	@Override
	public Long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}
}
