package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Jedna odpowiedź na wszystkie powody odmowy: token nieznany, wygasły, już
 * zużyty albo należący do skasowanego konta.
 *
 * <p>
 * Rozdzielenie ich (np. osobne {@code TOKEN_EXPIRED}) wyglądałoby na lepszy UX,
 * ale zamieniłoby endpoint w wyrocznię: „wygasł" potwierdza, że taki token
 * kiedyś istniał, czyli że pod danym adresem jest konto. Ta sama zasada, którą
 * moduł stosuje już do logowania (Claude.md §13) i do wykrywania ponownego
 * użycia refresh tokenu (§9).
 */
class InvalidPasswordResetTokenException extends ApiException {

	InvalidPasswordResetTokenException() {
		super(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD_RESET_TOKEN",
				"Link do resetu hasła jest nieprawidłowy lub wygasł");
	}
}
