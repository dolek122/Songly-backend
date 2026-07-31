package com.songly.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Ograniczenia na {@code newPassword} identyczne jak przy rejestracji i zmianie
 * hasła — reset nie może być furtką omijającą politykę haseł.
 *
 * <p>
 * Celowo bez pola {@code currentPassword}: cały sens tej ścieżki polega na tym,
 * że użytkownik obecnego hasła nie zna. Dowodem tożsamości jest tu dostęp do
 * skrzynki pocztowej, a nie znajomość hasła.
 */
public record PasswordResetConfirmRequest(@NotBlank String token,
		@NotBlank @Size(min = 8, max = 72) String newPassword) {
}
