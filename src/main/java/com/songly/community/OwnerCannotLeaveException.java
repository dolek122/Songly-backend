package com.songly.community;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class OwnerCannotLeaveException extends ApiException {

	OwnerCannotLeaveException() {
		super(HttpStatus.CONFLICT, "OWNER_CANNOT_LEAVE",
				"Właściciel nie może opuścić społeczności — usuń ją albo przekaż własność");
	}
}
