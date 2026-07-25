package com.songly.nowplaying;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class TooManyStreamsException extends ApiException {

	TooManyStreamsException(int maxPerUser) {
		super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_STREAMS",
				"Przekroczono limit otwartych strumieni presence (%d)".formatted(maxPerUser));
	}
}
