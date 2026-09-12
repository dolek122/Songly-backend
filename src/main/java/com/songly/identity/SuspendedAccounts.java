package com.songly.identity;

import java.util.Set;
import java.util.UUID;

/**
 * Publiczna granica modułu identity dla pytania „które konta są w tej chwili
 * zawieszone" (Claude.md §2).
 *
 * <p>
 * Powstało dla ADR 0026: zawieszenie odcinało konto od pisania i czytania (ADR
 * 0022), ale jego treści stały dalej — zawieszało się kogoś za nękanie, a
 * nękające posty zostawały. Ukrywanie wymaga więc tej listy w modułach content
 * i profile, a one nie mają prawa czytać tabeli {@code users}.
 *
 * <p>
 * Zwraca identyfikatory wszystkich zawieszonych, a nie odpowiedź per
 * użytkownik: konsumenci filtrują całe strony wyników, więc pytanie „czy ten
 * jest zawieszony" zadane osobno dla każdego wiersza dałoby N+1. Zbiór jest z
 * natury mały (zawieszenia nakłada ręcznie administrator) i <b>nie zawiera
 * zawieszeń wygasłych</b> — te nie liczą się nigdzie (ADR 0014).
 */
public interface SuspendedAccounts {

	Set<UUID> suspendedUserIds();
}
