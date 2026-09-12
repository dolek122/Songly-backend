package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Odwołać się może wyłącznie konto z <b>aktywnym</b> zawieszeniem — wygasłe to
 * brak zawieszenia (ADR 0022), więc nie ma się od czego odwoływać.
 */
class NoSuspensionToAppealException extends ApiException {

	NoSuspensionToAppealException() {
		super(HttpStatus.CONFLICT, "NO_SUSPENSION_TO_APPEAL", "Twoje konto nie jest zawieszone");
	}
}
