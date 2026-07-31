package com.songly.content;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Podany klucz zdjęcia nie pochodzi z wgrania zrobionego przez tego
 * użytkownika.
 *
 * <p>
 * Komunikat nie rozróżnia „taki klucz nie istnieje" od „istnieje, ale należy do
 * kogoś innego" — to druga informacja byłaby potwierdzeniem, że pod tym adresem
 * coś leży, a klucze zawierają cudze identyfikatory.
 */
class UnknownPostImageException extends ApiException {

	UnknownPostImageException() {
		super(HttpStatus.BAD_REQUEST, "UNKNOWN_POST_IMAGE", "Zdjęcie nie pochodzi z Twojego wgrania");
	}
}
