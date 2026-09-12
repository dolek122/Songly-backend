package com.songly.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Konto zostało zawieszone (ADR 0022). Publikowane <b>tylko przy przejściu</b>
 * z konta aktywnego na zawieszone, nie przy poprawianiu powodu trwającego
 * zawieszenia — ta sama zasada co „tylko nowy ban powiadamia" (ADR 0014).
 *
 * <p>
 * Zdarzenie niesie termin, ale <b>nie powód</b>: powód jest tekstem
 * administratora, a kanały push go nie przenoszą (ADR 0015). Po powód
 * zawieszony przychodzi sam, do {@code GET /api/v1/auth/me/suspension}.
 */
public record UserSuspendedEvent(UUID userId, UUID actorId, Instant expiresAt, Instant occurredAt) {
}
