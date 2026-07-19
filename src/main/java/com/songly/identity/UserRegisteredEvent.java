package com.songly.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Publikowane po zatwierdzonej (committed) rejestracji użytkownika. Jedyny
 * sposób, w jaki inne moduły (np. {@code profile}) dowiadują się o nowym
 * użytkowniku — bez sięgania po encję {@link User} spoza modułu identity.
 */
public record UserRegisteredEvent(UUID userId, String email, Instant occurredAt) {
}
