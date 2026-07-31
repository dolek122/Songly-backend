package com.songly.community;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class CannotModerateModeratorException extends ApiException {

	CannotModerateModeratorException() {
		super(HttpStatus.FORBIDDEN, "CANNOT_MODERATE_MODERATOR",
				"Moderatora może moderować tylko właściciel społeczności");
	}
}
