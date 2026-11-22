package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class CannotSuspendAdminException extends ApiException {

	CannotSuspendAdminException() {
		super(HttpStatus.FORBIDDEN, "CANNOT_SUSPEND_ADMIN",
				"Najpierw odbierz uprawnienia administratora, potem zawieś konto");
	}
}
