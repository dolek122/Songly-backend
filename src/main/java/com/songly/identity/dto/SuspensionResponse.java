package com.songly.identity.dto;

import java.time.Instant;

/**
 * Własny stan zawieszenia (ADR 0022) — jedyne miejsce, w którym zawieszony
 * poznaje powód.
 *
 * <p>
 * Powód jedzie wyłącznie tym kanałem, nie mailem: mail jest push i omija
 * ustawienia, więc powód-w-mailu zamieniałby cykl zawieszeń w doręczanie
 * dowolnego tekstu administratora do cudzej skrzynki (dokładnie ten sam
 * argument co przy powodzie banu, ADR 0015). Tu po treść trzeba samemu przyjść.
 *
 * <p>
 * {@code expiresAt} {@code null} przy {@code suspended=true} znaczy
 * bezterminowo; przy {@code suspended=false} wszystkie pozostałe pola są
 * {@code null}.
 */
public record SuspensionResponse(boolean suspended, String reason, Instant suspendedAt, Instant expiresAt) {
}
