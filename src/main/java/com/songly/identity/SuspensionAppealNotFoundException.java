package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/** Nie ma oczekującego odwołania, które dałoby się odrzucić. */
class SuspensionAppealNotFoundException extends ApiException {

	SuspensionAppealNotFoundException() {
		super(HttpStatus.NOT_FOUND, "SUSPENSION_APPEAL_NOT_FOUND", "Brak oczekującego odwołania");
	}
}
