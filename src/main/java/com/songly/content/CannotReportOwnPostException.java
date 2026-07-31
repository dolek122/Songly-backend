package com.songly.content;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class CannotReportOwnPostException extends ApiException {

	CannotReportOwnPostException() {
		super(HttpStatus.BAD_REQUEST, "CANNOT_REPORT_OWN_POST", "Nie można zgłosić własnego posta");
	}
}
