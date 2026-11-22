package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class InvalidVerificationTokenException extends ApiException {

	InvalidVerificationTokenException() {
		super(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_TOKEN",
				"Link potwierdzający jest nieprawidłowy lub wygasł");
	}
}
