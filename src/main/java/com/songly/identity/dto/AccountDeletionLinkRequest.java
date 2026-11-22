package com.songly.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record AccountDeletionLinkRequest(@NotBlank @Email String email) {

	public AccountDeletionLinkRequest {
		email = EmailAddresses.normalize(email);
	}
}
