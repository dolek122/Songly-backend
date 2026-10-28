package com.songly.identity.dto;

import com.songly.common.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(@NotBlank @Email String email,
		@NotBlank @Size(min = 8, max = 72) @MaxUtf8Bytes(72) String password) {

	public RegisterRequest {
		email = EmailAddresses.normalize(email);
	}
}
