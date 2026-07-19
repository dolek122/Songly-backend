package com.songly.content;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class NotResourceOwnerException extends ApiException {

	NotResourceOwnerException() {
		super(HttpStatus.FORBIDDEN, "NOT_RESOURCE_OWNER", "Tylko autor może usunąć ten zasób");
	}
}
