package com.songly.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {

	public LoginRequest {
		email = EmailAddresses.normalize(email);
	}
}
