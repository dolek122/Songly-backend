package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Administrator nie zawiesza samego siebie (ADR 0022) — straciłby dostęp do
 * endpointu, którym mógłby to cofnąć, a odzyskanie konta wymagałoby wtedy
 * ręcznego {@code UPDATE} w bazie, czyli dokładnie tego, co ADR 0021 usunął z
 * procedur.
 */
class CannotSuspendSelfException extends ApiException {

	CannotSuspendSelfException() {
		super(HttpStatus.FORBIDDEN, "CANNOT_SUSPEND_SELF", "Nie można zawiesić własnego konta");
	}
}
