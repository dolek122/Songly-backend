package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class SuspensionAppealAlreadySubmittedException extends ApiException {

	SuspensionAppealAlreadySubmittedException() {
		super(HttpStatus.CONFLICT, "SUSPENSION_APPEAL_ALREADY_SUBMITTED",
				"Odwołanie od tego zawieszenia zostało już złożone");
	}
}
