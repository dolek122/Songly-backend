package com.songly.notification.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Oba pola obowiązkowe: {@code PUT} zastępuje ustawienia w całości, więc
 * pominięte pole znaczyłoby „zostaw jak było" i robiłoby z tego po cichu
 * aktualizację częściową.
 */
public record UpdateMailPreferencesRequest(@NotNull Boolean decisionsAboutMe, @NotNull Boolean moderationQueue) {
}
