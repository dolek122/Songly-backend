package com.songly.identity;

/**
 * Stan konta potrzebny do autoryzacji żądania (ADR 0022): czy administrator i
 * czy zawieszony.
 *
 * <p>
 * Oba pola przychodzą <b>jednym</b> zapytaniem, bo sprawdzamy je przy każdym
 * żądaniu (ADR 0006). Dwa osobne pytania podwoiłyby ten koszt, a to właśnie
 * odczyt na żywo daje tu najcenniejszą własność: zawieszenie i odebranie roli
 * działają natychmiast, bez unieważniania sesji.
 */
public record AccountStatus(boolean admin, boolean suspended) {

	static final AccountStatus UNKNOWN = new AccountStatus(false, false);
}
