package com.songly.nowplaying;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class InvalidConnectTicketException extends ApiException {

	InvalidConnectTicketException() {
		super(HttpStatus.BAD_REQUEST, "INVALID_CONNECT_TICKET",
				"Nie udało się dokończyć połączenia ze Spotify. Link wygasł albo połączenie rozpoczęto na innym koncie"
						+ " — połącz jeszcze raz z aplikacji");
	}
}
