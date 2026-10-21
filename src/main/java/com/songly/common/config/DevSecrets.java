package com.songly.common.config;

/**
 * Odmowa startu z sekretem deweloperskim tam, gdzie wymagane są prawdziwe.
 *
 * <p>
 * {@code application.properties} ma domyślne sekrety, żeby lokalnie nic nie
 * trzeba było konfigurować. Są publiczne, bo leżą w repo, i oznaczone
 * {@value #DEV_MARKER}. Gdy zmienna środowiskowa z prawdziwym sekretem nie
 * zostanie ustawiona, aplikacja wstawała po cichu z wartością domyślną. Przy
 * {@code JWT_SECRET} znaczyło to, że każdy mógł podpisać token dowolnego konta,
 * także administratora, a z zewnątrz nie było tego widać.
 *
 * <p>
 * Ten sam układ co poczta i push (Claude.md §9): środowisko, które deklaruje
 * {@code songly.security.production-secrets-required=true} (na Renderze robi to
 * {@code render.yaml}), nie wystartuje, zamiast działać z kluczem znanym
 * każdemu. Klasa nie zna domeny — nazwy propercji podaje moduł, który sekretu
 * używa.
 */
public final class DevSecrets {

	/** Propercja, którą środowisko deklaruje, że sekrety muszą być prawdziwe. */
	public static final String PRODUCTION_REQUIRED_PROPERTY = "songly.security.production-secrets-required";

	/**
	 * Znacznik wszystkich sekretów deweloperskich w {@code application.properties}.
	 */
	public static final String DEV_MARKER = "dev-only";

	private DevSecrets() {
	}

	/**
	 * @param productionRequired
	 *            wartość {@link #PRODUCTION_REQUIRED_PROPERTY}
	 * @param effectiveValue
	 *            sekret w postaci, w której widać znacznik (np. klucz już
	 *            zdekodowany z Base64)
	 * @param environmentVariable
	 *            zmienna, którą trzeba ustawić — trafia do komunikatu błędu
	 */
	public static void rejectDevValue(boolean productionRequired, String effectiveValue, String environmentVariable) {
		if (productionRequired && effectiveValue != null && effectiveValue.contains(DEV_MARKER)) {
			throw new IllegalStateException(environmentVariable + " ma wartość deweloperską z repozytorium, a to "
					+ "środowisko wymaga prawdziwych sekretów (" + PRODUCTION_REQUIRED_PROPERTY + "=true). Ustaw "
					+ environmentVariable + " — wartość domyślna jest publiczna.");
		}
	}
}
