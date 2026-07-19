package com.songly.social;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class AlreadyFriendsException extends ApiException {

	AlreadyFriendsException() {
		super(HttpStatus.CONFLICT, "ALREADY_FRIENDS", "Użytkownicy są już znajomymi");
	}
}
