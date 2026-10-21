package com.songly.nowplaying;

import com.songly.common.config.DevSecrets;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-256-GCM: losowy 12-bajtowy IV doklejany przed szyfrogramem. */
@Component
class TokenEncryptor {

	private static final int IV_LENGTH = 12;
	private static final int TAG_LENGTH_BITS = 128;

	private final SecretKey key;
	private final SecureRandom secureRandom = new SecureRandom();

	/**
	 * Klucz deweloperski z repo odpada tam, gdzie wymagane są prawdziwe sekrety: z
	 * nim każdy, kto ma zrzut bazy, odszyfrowałby tokeny Spotify wszystkich
	 * użytkowników (Claude.md §9).
	 */
	TokenEncryptor(NowPlayingProperties properties,
			@Value("${" + DevSecrets.PRODUCTION_REQUIRED_PROPERTY + ":false}") boolean productionSecretsRequired) {
		byte[] keyBytes = Base64.getDecoder().decode(properties.getTokenEncryptionKey());
		if (keyBytes.length != 32) {
			throw new IllegalStateException(
					"songly.now-playing.token-encryption-key musi być 32 bajtami w Base64 (AES-256)");
		}
		DevSecrets.rejectDevValue(productionSecretsRequired, new String(keyBytes, StandardCharsets.UTF_8),
				"NOW_PLAYING_TOKEN_KEY");
		this.key = new SecretKeySpec(keyBytes, "AES");
	}

	String encrypt(String plaintext) {
		try {
			byte[] iv = new byte[IV_LENGTH];
			secureRandom.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext);
			return Base64.getEncoder().encodeToString(buffer.array());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Szyfrowanie tokenu nie powiodło się", e);
		}
	}

	String decrypt(String encoded) {
		try {
			ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(encoded));
			byte[] iv = new byte[IV_LENGTH];
			buffer.get(iv);
			byte[] ciphertext = new byte[buffer.remaining()];
			buffer.get(ciphertext);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
			return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Odszyfrowanie tokenu nie powiodło się", e);
		}
	}
}
