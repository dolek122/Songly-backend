package com.songly.social;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class FriendRequestNotFoundException extends ApiException {

	FriendRequestNotFoundException() {
		super(HttpStatus.NOT_FOUND, "FRIEND_REQUEST_NOT_FOUND", "Zaproszenie nie zostało znalezione");
	}
}
