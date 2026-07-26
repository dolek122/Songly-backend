package com.songly.content;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Ten sam kod błędu, co przy własnych endpointach społeczności — dla klienta
 * „nie ma takiej społeczności" znaczy to samo niezależnie od tego, który moduł
 * obsłużył żądanie. Osobna klasa, bo wyjątek modułu community jest jego
 * prywatnym wnętrzem (Claude.md §2).
 */
class UnknownCommunityException extends ApiException {

	UnknownCommunityException() {
		super(HttpStatus.NOT_FOUND, "COMMUNITY_NOT_FOUND", "Społeczność nie została znaleziona");
	}
}
