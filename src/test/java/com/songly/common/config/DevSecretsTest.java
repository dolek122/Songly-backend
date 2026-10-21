package com.songly.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DevSecretsTest {

	private static final String DEV_JWT_SECRET = "dev-only-secret-change-me-in-every-real-environment-32bytes+";

	@Test
	void rejectsDevValue_whereProductionSecretsAreRequired() {
		assertThatThrownBy(() -> DevSecrets.rejectDevValue(true, DEV_JWT_SECRET, "JWT_SECRET"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET")
				// Komunikat nie może powtarzać samego sekretu — trafia do logów startu.
				.message().doesNotContain(DEV_JWT_SECRET);
	}

	/**
	 * Lokalnie wartości deweloperskie są właśnie po to, żeby nic nie konfigurować.
	 */
	@Test
	void acceptsDevValue_locally() {
		assertThatCode(() -> DevSecrets.rejectDevValue(false, DEV_JWT_SECRET, "JWT_SECRET")).doesNotThrowAnyException();
	}

	@Test
	void acceptsRealValue_whereProductionSecretsAreRequired() {
		assertThatCode(() -> DevSecrets.rejectDevValue(true, "K3m9...prawdziwy-losowy-sekret...", "JWT_SECRET"))
				.doesNotThrowAnyException();
	}
}
