package com.songly.community;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/** Odwołać się może wyłącznie osoba z banem aktywnym — wygasły to brak bana. */
class NoBanToAppealException extends ApiException {

	NoBanToAppealException() {
		super(HttpStatus.CONFLICT, "NO_BAN_TO_APPEAL", "Nie masz aktywnego bana w tej społeczności");
	}
}
