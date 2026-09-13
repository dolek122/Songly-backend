package com.songly.profile;

import java.util.UUID;

/**
 * Ktoś zmienił swoje ulubione gatunki albo zniknął razem z kontem — czyli
 * popularność gatunków w bazie jest inna niż w cache'u (ADR 0032).
 *
 * <p>
 * Zdarzenie <b>wewnętrzne</b> modułu (package-private), jak
 * {@code PasswordResetRequestedEvent} w identity: nie jest częścią API dla
 * innych modułów, tylko sposobem na przeniesienie roboty za commit.
 */
record FavoriteGenresChangedEvent(UUID userId) {
}
