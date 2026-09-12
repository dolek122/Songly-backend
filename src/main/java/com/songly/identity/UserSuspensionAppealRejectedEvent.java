package com.songly.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Odwołanie odrzucone — zawieszenie zostaje w mocy (ADR 0023). Dla tego
 * zawieszenia decyzja jest ostateczna: jedno odwołanie na zawieszenie, a świeże
 * prawo daje dopiero nowe zawieszenie.
 */
public record UserSuspensionAppealRejectedEvent(UUID userId, UUID actorId, Instant occurredAt) {
}
