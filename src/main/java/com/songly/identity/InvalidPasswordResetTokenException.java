package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class InvalidPasswordResetTokenException extends ApiException {

	InvalidPasswordResetTokenException() {
		super(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD_RESET_TOKEN",
				"Link do resetu hasła jest nieprawidłowy lub wygasł");
	}
}
