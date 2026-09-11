package com.songly.common.media;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

public class EmptyUploadException extends ApiException {

	public EmptyUploadException() {
		super(HttpStatus.BAD_REQUEST, "EMPTY_UPLOAD", "Przesłany plik jest pusty");
	}
}
