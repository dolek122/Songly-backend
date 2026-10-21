package com.songly.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Formularz strony usuwania konta (ADR 0043). Adres normalizowany jak przy
 * logowaniu (ADR 0042) — inaczej „Jan@…" wpisane na stronie nie znalazłoby
 * konta założonego jako „jan@…".
 */
public record AccountDeletionLinkRequest(@NotBlank @Email String email) {

	public AccountDeletionLinkRequest {
		email = EmailAddresses.normalize(email);
	}
}
