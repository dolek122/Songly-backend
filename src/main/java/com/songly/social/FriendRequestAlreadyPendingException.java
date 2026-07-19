package com.songly.social;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class FriendRequestAlreadyPendingException extends ApiException {

	FriendRequestAlreadyPendingException() {
		super(HttpStatus.CONFLICT, "FRIEND_REQUEST_ALREADY_PENDING", "Zaproszenie już oczekuje na odpowiedź");
	}
}
