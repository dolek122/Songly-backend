package com.songly.content;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class ReportRateLimitExceededException extends ApiException {

	ReportRateLimitExceededException() {
		super(HttpStatus.TOO_MANY_REQUESTS, "REPORT_RATE_LIMIT_EXCEEDED",
				"Zbyt wiele zgłoszeń w krótkim czasie, spróbuj ponownie później");
	}
}
