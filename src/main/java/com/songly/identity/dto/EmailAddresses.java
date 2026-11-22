package com.songly.identity.dto;

import java.util.Locale;

final class EmailAddresses {

	private EmailAddresses() {
	}

	static String normalize(String email) {
		return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
	}
}
