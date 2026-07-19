package com.songly.social;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class UserBlockedException extends ApiException {

	UserBlockedException() {
		super(HttpStatus.CONFLICT, "USER_BLOCKED", "Akcja niedostępna z powodu blokady");
	}
}
