package com.songly.content;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class UnknownPostImageException extends ApiException {

	UnknownPostImageException() {
		super(HttpStatus.BAD_REQUEST, "UNKNOWN_POST_IMAGE",
				"Zdjęcie wygasło albo zostało już użyte. Wgraj je ponownie.");
	}
}
