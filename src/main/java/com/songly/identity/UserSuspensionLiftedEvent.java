package com.songly.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Zawieszenie zdjęte przed terminem (ADR 0022). Nie powstaje przy wygaśnięciu —
 * wygasanie jest leniwe i nie ma chwili, w której cokolwiek się dzieje (ADR
 * 0014), więc powiadomienie o powrocie dostaje tylko ten, komu konto przywrócił
 * człowiek.
 */
public record UserSuspensionLiftedEvent(UUID userId, UUID actorId, Instant occurredAt) {
}
