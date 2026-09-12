package com.songly.community;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/** Brak sprawy oczekującej na drugą instancję (ADR 0028). */
class EscalatedAppealNotFoundException extends ApiException {

	EscalatedAppealNotFoundException() {
		super(HttpStatus.NOT_FOUND, "ESCALATED_APPEAL_NOT_FOUND", "Brak odwołania oczekującego na administrację");
	}
}
