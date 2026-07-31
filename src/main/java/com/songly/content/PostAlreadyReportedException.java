package com.songly.content;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class PostAlreadyReportedException extends ApiException {

	PostAlreadyReportedException() {
		super(HttpStatus.CONFLICT, "POST_ALREADY_REPORTED", "Ten post został już przez Ciebie zgłoszony");
	}
}
