package com.songly.community;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class CannotAssignOwnerRoleException extends ApiException {

	CannotAssignOwnerRoleException() {
		super(HttpStatus.BAD_REQUEST, "CANNOT_ASSIGN_OWNER_ROLE",
				"Roli właściciela nie nadaje się przez zmianę roli członka");
	}
}
