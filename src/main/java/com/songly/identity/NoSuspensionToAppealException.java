package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class NoSuspensionToAppealException extends ApiException {

	NoSuspensionToAppealException() {
		super(HttpStatus.CONFLICT, "NO_SUSPENSION_TO_APPEAL", "Twoje konto nie jest zawieszone");
	}
}
