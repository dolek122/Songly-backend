package com.songly.community;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Jedno odwołanie na ban — niezależnie od tego, czy poprzednie czeka, czy
 * zostało odrzucone. To strukturalny anty-spam kanału odwołań (ADR 0017).
 */
class BanAppealAlreadySubmittedException extends ApiException {

	BanAppealAlreadySubmittedException() {
		super(HttpStatus.CONFLICT, "BAN_APPEAL_ALREADY_SUBMITTED", "Odwołanie od tego bana zostało już złożone");
	}
}
