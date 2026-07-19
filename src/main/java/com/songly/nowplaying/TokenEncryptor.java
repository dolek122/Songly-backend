package com.songly.nowplaying;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** AES-256-GCM: losowy 12-bajtowy IV doklejany przed szyfrogramem. */
@Component
class TokenEncryptor {

	private static final int IV_LENGTH = 12;
	private static final int TAG_LENGTH_BITS = 128;

	private final SecretKey key;
	private final SecureRandom secureRandom = new SecureRandom();

	TokenEncryptor(NowPlayingProperties properties) {
		byte[] keyBytes = Base64.getDecoder().decode(properties.getTokenEncryptionKey());
		if (keyBytes.length != 32) {
			throw new IllegalStateException(
					"songly.now-playing.token-encryption-key musi być 32 bajtami w Base64 (AES-256)");
		}
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
