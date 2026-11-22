package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class NewPasswordSameAsCurrentException extends ApiException {

	NewPasswordSameAsCurrentException() {
		super(HttpStatus.BAD_REQUEST, "NEW_PASSWORD_SAME_AS_CURRENT", "Nowe hasło musi różnić się od obecnego");
	}
}
