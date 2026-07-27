package com.songly.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Ograniczenia na {@code newPassword} są takie same jak przy rejestracji —
 * gdyby były luźniejsze, zmiana hasła byłaby obejściem własnej polityki haseł.
 * Górne 72 to limit bcrypta, nie nasza decyzja: dłuższe hasło zostałoby po
 * cichu obcięte.
 */
public record ChangePasswordRequest(@NotBlank String currentPassword,
		@NotBlank @Size(min = 8, max = 72) String newPassword) {
}
