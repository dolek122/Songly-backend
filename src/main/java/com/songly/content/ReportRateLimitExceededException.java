package com.songly.content;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Limit zgłoszeń na osobę (ADR 0018). Ten sam status co rate limiter na
 * publicznych endpointach, ale osobny kod — tamten liczy per IP przed
 * uwierzytelnieniem, ten per użytkownik w granicach domeny.
 */
class ReportRateLimitExceededException extends ApiException {

	ReportRateLimitExceededException() {
		super(HttpStatus.TOO_MANY_REQUESTS, "REPORT_RATE_LIMIT_EXCEEDED",
				"Zbyt wiele zgłoszeń w krótkim czasie, spróbuj ponownie później");
	}
}
