package com.songly.social;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class FriendRequestAlreadyHandledException extends ApiException {

	FriendRequestAlreadyHandledException() {
		super(HttpStatus.CONFLICT, "FRIEND_REQUEST_ALREADY_HANDLED", "Zaproszenie zostało już obsłużone");
	}
}
