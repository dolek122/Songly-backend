package com.songly.nowplaying;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Kod autoryzacyjny z redirectu Spotify jest zużyty, wygasły albo podrobiony.
 * Błąd klienta (400), nie awaria providera — inaczej odświeżenie callbacku w
 * przeglądarce zgłaszałoby 502.
 */
class InvalidAuthorizationCodeException extends ApiException {

	InvalidAuthorizationCodeException() {
		super(HttpStatus.BAD_REQUEST, "INVALID_AUTHORIZATION_CODE", "Kod autoryzacyjny jest nieprawidłowy lub wygasł");
	}
}
