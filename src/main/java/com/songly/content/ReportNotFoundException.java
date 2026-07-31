package com.songly.content;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class ReportNotFoundException extends ApiException {

	ReportNotFoundException() {
		super(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "Nie znaleziono zgłoszenia");
	}
}
