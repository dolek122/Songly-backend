package com.songly.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Publikowane po zatwierdzonej (committed) rejestracji użytkownika. Jedyny
 * sposób, w jaki inne moduły (np. {@code profile}) dowiadują się o nowym
 * użytkowniku — bez sięgania po encję {@link User} spoza modułu identity.
 *
 * <p>
 * Celowo <b>bez adresu e-mail</b> (ADR 0046). Niósł go wyłącznie po to, żeby
 * profil zrobił z niego domyślną nazwę wyświetlaną, a ta wystawiała część
 * adresu publicznie. Inne moduły dostają adres tylko do wysyłki, przez
 * {@link UserEmailDirectory} — zdarzenie domenowe nie jest miejscem, którym
 * adres ma się rozchodzić po aplikacji.
 */
public record UserRegisteredEvent(UUID userId, Instant occurredAt) {
}
