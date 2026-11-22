package com.songly.profile;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class TooManyUserIdsException extends ApiException {

	TooManyUserIdsException(int limit) {
		super(HttpStatus.BAD_REQUEST, "TOO_MANY_USER_IDS", "Można zapytać o najwyżej " + limit + " użytkowników naraz");
	}
}
