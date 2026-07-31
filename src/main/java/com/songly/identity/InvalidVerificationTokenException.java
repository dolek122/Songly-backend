package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Jedna odpowiedź na wszystkie powody odmowy: token nieznany, wygasły, już
 * zużyty albo dotyczący adresu, na którym w międzyczasie powstało konto.
 *
 * <p>
 * Rozdzielenie ich odtworzyłoby wyrocznię, którą cały ten przepływ likwiduje —
 * „ten adres ma już konto" jest dokładnie tą informacją, której rejestracja
 * przestała udzielać.
 */
class InvalidVerificationTokenException extends ApiException {

	InvalidVerificationTokenException() {
		super(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_TOKEN",
				"Link potwierdzający jest nieprawidłowy lub wygasł");
	}
}
