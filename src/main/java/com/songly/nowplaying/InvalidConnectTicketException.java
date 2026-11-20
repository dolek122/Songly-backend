package com.songly.nowplaying;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Bilet z przekierowania jest nieznany, zużyty, wygasły albo należy do
 * połączenia rozpoczętego na innym koncie (ADR 0050). Jeden kod na wszystkie
 * przypadki: dla właściwego użytkownika każdy z nich znaczy to samo — połącz
 * jeszcze raz.
 */
class InvalidConnectTicketException extends ApiException {

	InvalidConnectTicketException() {
		super(HttpStatus.BAD_REQUEST, "INVALID_CONNECT_TICKET",
				"Nie udało się dokończyć połączenia ze Spotify. Link wygasł albo połączenie rozpoczęto na innym koncie"
						+ " — połącz jeszcze raz z aplikacji");
	}
}
