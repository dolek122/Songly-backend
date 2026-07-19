package com.songly.profile;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class UnknownGenreException extends ApiException {

	UnknownGenreException() {
		super(HttpStatus.BAD_REQUEST, "UNKNOWN_GENRE", "Jeden lub więcej gatunków nie istnieje");
	}
}
