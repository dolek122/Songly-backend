package com.songly.identity.dto;

import java.util.Locale;

/**
 * Jedna postać adresu e-mail w całym module (ADR 0042): bez białych znaków na
 * brzegach i małymi literami.
 *
 * <p>
 * Normalizujemy na granicy, w konstruktorach DTO, a nie w serwisach: walidacja
 * {@code @Email} działa wtedy już na postaci znormalizowanej, więc spacja
 * dopisana przez autouzupełnianie klawiatury nie kończy się 400, a żaden serwis
 * nie może zapomnieć o normalizacji przy kolejnym zapytaniu po adresie.
 *
 * <p>
 * Małe litery, choć RFC 5321 pozwala, żeby część lokalna rozróżniała wielkość
 * liter: żaden popularny dostawca poczty tego nie robi, a klawiatura w
 * telefonie zaczyna adres wielką literą sama z siebie. Bez tego „Jan@gmail.com"
 * przy rejestracji i „jan@gmail.com" przy logowaniu to dwa różne konta, a
 * drugie logowanie kończy się „błędnym hasłem".
 */
final class EmailAddresses {

	private EmailAddresses() {
	}

	static String normalize(String email) {
		return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
	}
}
