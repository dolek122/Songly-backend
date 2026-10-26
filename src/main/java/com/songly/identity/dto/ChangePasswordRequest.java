package com.songly.identity.dto;

import com.songly.common.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Ograniczenia na {@code newPassword} są takie same jak przy rejestracji —
 * gdyby były luźniejsze, zmiana hasła byłaby obejściem własnej polityki haseł.
 * Górne 72 to limit bcrypta, nie nasza decyzja, i liczy się go w bajtach
 * ({@link MaxUtf8Bytes}): dłuższe hasło bcrypt odrzuca wyjątkiem.
 */
public record ChangePasswordRequest(@NotBlank String currentPassword,
		@NotBlank @Size(min = 8, max = 72) @MaxUtf8Bytes(72) String newPassword) {
}
