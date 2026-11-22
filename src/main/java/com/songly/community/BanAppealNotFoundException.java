package com.songly.community;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class BanAppealNotFoundException extends ApiException {

	BanAppealNotFoundException() {
		super(HttpStatus.NOT_FOUND, "BAN_APPEAL_NOT_FOUND", "Brak oczekującego odwołania");
	}
}
