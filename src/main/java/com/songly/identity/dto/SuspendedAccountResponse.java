package com.songly.identity.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Wiersz wykazu zawieszonych kont dla administratora (ADR 0022). Bez adresu
 * e-mail: do identyfikacji służy {@code userId}, a wykaz ma pokazywać stan
 * moderacyjny, nie być wyszukiwarką danych kontaktowych (Claude.md §9).
 */
public record SuspendedAccountResponse(UUID userId, String reason, Instant suspendedAt, Instant expiresAt) {
}
