package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class InvalidRefreshTokenException extends ApiException {

	InvalidRefreshTokenException() {
		super(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token jest nieprawidłowy lub wygasł");
	}
}
