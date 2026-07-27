package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Zmiana hasła na to samo byłaby operacją bez żadnego skutku poza wylogowaniem
 * użytkownika ze wszystkich urządzeń — czyli samą szkodą. Odrzucamy ją wprost,
 * zamiast pozwolić mu zastanawiać się, czemu nagle musi się wszędzie logować.
 */
class NewPasswordSameAsCurrentException extends ApiException {

	NewPasswordSameAsCurrentException() {
		super(HttpStatus.BAD_REQUEST, "NEW_PASSWORD_SAME_AS_CURRENT", "Nowe hasło musi różnić się od obecnego");
	}
}
