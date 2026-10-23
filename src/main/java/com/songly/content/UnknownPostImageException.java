package com.songly.content;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Podanego klucza zdjęcia nie ma w rejestrze wgrań tego użytkownika (ADR 0048).
 *
 * <p>
 * Uczciwy klient trafia tu wyłącznie wtedy, gdy wgranie przepadło: czekało
 * ponad dobę, wyparły je nowsze albo zostało już przypięte do innego posta.
 * Komunikat mówi więc, co z tym zrobić, a nie oskarża o cudzy klucz — tak
 * brzmiał do ADR 0048, gdy jedynym sprawdzeniem był kształt klucza.
 *
 * <p>
 * Kod i komunikat są te same dla klucza zmyślonego i cudzego, i to celowo:
 * rozróżnienie byłoby potwierdzeniem, że pod tym adresem coś leży, a klucze
 * zawierają cudze identyfikatory.
 */
class UnknownPostImageException extends ApiException {

	UnknownPostImageException() {
		super(HttpStatus.BAD_REQUEST, "UNKNOWN_POST_IMAGE",
				"Zdjęcie wygasło albo zostało już użyte. Wgraj je ponownie.");
	}
}
