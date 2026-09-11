package com.songly.common.media;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

public class UnsupportedImageException extends ApiException {

	public UnsupportedImageException() {
		super(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_IMAGE", "Obsługiwane formaty obrazu to JPEG, PNG i WebP");
	}
}
