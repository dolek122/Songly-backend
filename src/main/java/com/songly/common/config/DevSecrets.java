package com.songly.common.config;

public final class DevSecrets {

	public static final String PRODUCTION_REQUIRED_PROPERTY = "songly.security.production-secrets-required";

	public static final String DEV_MARKER = "dev-only";

	private DevSecrets() {
	}

	public static void rejectDevValue(boolean productionRequired, String effectiveValue, String environmentVariable) {
		if (productionRequired && effectiveValue != null && effectiveValue.contains(DEV_MARKER)) {
			throw new IllegalStateException(environmentVariable + " ma wartość deweloperską z repozytorium, a to "
					+ "środowisko wymaga prawdziwych sekretów (" + PRODUCTION_REQUIRED_PROPERTY + "=true). Ustaw "
					+ environmentVariable + " — wartość domyślna jest publiczna.");
		}
	}
}
