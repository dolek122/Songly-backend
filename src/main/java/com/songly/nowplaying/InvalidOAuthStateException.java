package com.songly.nowplaying;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class InvalidOAuthStateException extends ApiException {

	InvalidOAuthStateException() {
		super(HttpStatus.BAD_REQUEST, "INVALID_OAUTH_STATE", "Parametr state jest nieprawidłowy lub wygasł");
	}
}
