package com.songly.nowplaying;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class InvalidAuthorizationCodeException extends ApiException {

	InvalidAuthorizationCodeException() {
		super(HttpStatus.BAD_REQUEST, "INVALID_AUTHORIZATION_CODE", "Kod autoryzacyjny jest nieprawidłowy lub wygasł");
	}
}
