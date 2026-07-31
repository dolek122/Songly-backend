package com.songly.community;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class CannotModerateOwnerException extends ApiException {

	CannotModerateOwnerException() {
		super(HttpStatus.FORBIDDEN, "CANNOT_MODERATE_OWNER", "Właściciela społeczności nie da się moderować");
	}
}
