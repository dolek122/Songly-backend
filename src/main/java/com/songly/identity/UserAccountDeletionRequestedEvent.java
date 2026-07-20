package com.songly.identity;

import java.util.UUID;

/**
 * Publikowane synchronicznie (nie {@code @TransactionalEventListener
 * AFTER_COMMIT}, w przeciwieństwie do {@link UserRegisteredEvent}) w tej samej
 * transakcji co usunięcie konta — wiersze w innych modułach mają FK do
 * {@code users} bez ON DELETE CASCADE, więc muszą zniknąć przed usunięciem
 * wiersza użytkownika, inaczej baza odrzuci DELETE.
 */
public record UserAccountDeletionRequestedEvent(UUID userId) {
}
