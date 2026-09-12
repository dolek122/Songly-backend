package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Jedno odwołanie na zawieszenie — niezależnie od tego, czy poprzednie czeka,
 * czy zostało odrzucone. To strukturalny anty-spam kanału, który z natury
 * prowadzi od ukaranego do karzącego (ADR 0023, jak ADR 0017).
 */
class SuspensionAppealAlreadySubmittedException extends ApiException {

	SuspensionAppealAlreadySubmittedException() {
		super(HttpStatus.CONFLICT, "SUSPENSION_APPEAL_ALREADY_SUBMITTED",
				"Odwołanie od tego zawieszenia zostało już złożone");
	}
}
