package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class InvalidCredentialsException extends ApiException {

	InvalidCredentialsException() {
		super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Nieprawidłowy e-mail lub hasło");
	}
}
