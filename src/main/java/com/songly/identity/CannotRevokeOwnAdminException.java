package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class CannotRevokeOwnAdminException extends ApiException {

	CannotRevokeOwnAdminException() {
		super(HttpStatus.FORBIDDEN, "CANNOT_REVOKE_OWN_ADMIN",
				"Nie można odebrać uprawnień administratora samemu sobie");
	}
}
