package com.songly.common.media;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Pusty plik.
 *
 * <p>
 * Bez tego pustka doszłaby do rozpoznawania formatu i wróciła jako
 * „nieobsługiwany format obrazu" — komunikat prawdziwy, ale mylący: problemem
 * jest brak pliku, a nie jego typ, a użytkownik szukałby błędu w konwerterze
 * zamiast w tym, że wybrał pusty plik.
 */
public class EmptyUploadException extends ApiException {

	public EmptyUploadException() {
		super(HttpStatus.BAD_REQUEST, "EMPTY_UPLOAD", "Przesłany plik jest pusty");
	}
}
