package com.songly.community;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Do drugiej instancji idzie się <b>wyłącznie</b> po odmowie pierwszej (ADR
 * 0028). Nie ma odwołania, czeka jeszcze na moderację albo administracja już
 * orzekła — w każdym z tych przypadków nie ma czego eskalować.
 */
class BanAppealNotEscalatableException extends ApiException {

	BanAppealNotEscalatableException() {
		super(HttpStatus.CONFLICT, "BAN_APPEAL_NOT_ESCALATABLE",
				"Do administracji można przekazać tylko odwołanie odrzucone przez moderację społeczności");
	}
}
