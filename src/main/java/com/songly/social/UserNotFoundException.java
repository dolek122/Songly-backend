package com.songly.social;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class UserNotFoundException extends ApiException {

	UserNotFoundException() {
		super(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Użytkownik nie został znaleziony");
	}
}
