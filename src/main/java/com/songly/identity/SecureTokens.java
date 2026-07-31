package com.songly.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Losowanie i skracanie tokenów okazicielskich (refresh token, token resetu
 * hasła).
 *
 * <p>
 * Wspólne miejsce, bo obie ścieżki muszą trzymać się dokładnie tej samej
 * zasady: token wychodzi na zewnątrz raz, a w bazie zostaje wyłącznie jego
 * skrót. Rozjazd między dwiema kopiami tej logiki byłby błędem bezpieczeństwa,
 * którego nie widać w żadnym teście funkcjonalnym.
 *
 * <p>
 * SHA-256 bez soli i bez rozciągania jest tu <b>wystarczające</b>, w
 * przeciwieństwie do haseł: token to 256 bitów z {@link SecureRandom}, więc nie
 * ma czego zgadywać słownikiem ani tęczową tablicą. Skrót chroni przed
 * wyciekiem bazy, a nie przed odgadnięciem.
 */
final class SecureTokens {

	private static final SecureRandom RANDOM = new SecureRandom();

	private SecureTokens() {
	}

	static String generate() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String hash(String token) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return Base64.getEncoder().encodeToString(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
