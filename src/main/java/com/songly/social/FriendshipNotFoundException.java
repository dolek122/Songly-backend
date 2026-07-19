package com.songly.social;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class FriendshipNotFoundException extends ApiException {

	FriendshipNotFoundException() {
		super(HttpStatus.NOT_FOUND, "FRIENDSHIP_NOT_FOUND", "Znajomość nie została znaleziona");
	}
}
