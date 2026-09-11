package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Konto o podanym identyfikatorze nie istnieje.
 *
 * <p>
 * Ten 404 <b>nie jest</b> wyrocznią na istnienie kont, której cały przepływ
 * rejestracji unika (ADR 0009): endpoint wymaga uprawnień administratora, a
 * pytającym jest ktoś, kto i tak ma dostęp do całej analityki. Wobec osoby
 * postronnej nic się nie zmienia.
 */
class AdminUserNotFoundException extends ApiException {

	AdminUserNotFoundException() {
		super(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Nie ma konta o podanym identyfikatorze");
	}
}
