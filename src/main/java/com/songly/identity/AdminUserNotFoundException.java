package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class AdminUserNotFoundException extends ApiException {

	AdminUserNotFoundException() {
		super(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Nie ma konta o podanym identyfikatorze");
	}
}
