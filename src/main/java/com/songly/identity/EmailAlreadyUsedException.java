package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class EmailAlreadyUsedException extends ApiException {

	EmailAlreadyUsedException(String email) {
		super(HttpStatus.CONFLICT, "EMAIL_ALREADY_USED", "Konto z adresem " + email + " już istnieje");
	}
}
