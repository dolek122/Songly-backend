package com.songly.community;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class BanAppealAlreadySubmittedException extends ApiException {

	BanAppealAlreadySubmittedException() {
		super(HttpStatus.CONFLICT, "BAN_APPEAL_ALREADY_SUBMITTED", "Odwołanie od tego bana zostało już złożone");
	}
}
