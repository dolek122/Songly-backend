package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class CannotSuspendSelfException extends ApiException {

	CannotSuspendSelfException() {
		super(HttpStatus.FORBIDDEN, "CANNOT_SUSPEND_SELF", "Nie można zawiesić własnego konta");
	}
}
