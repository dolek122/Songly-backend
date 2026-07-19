package com.songly.nowplaying;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class TokenEncryptorTest {

	private static NowPlayingProperties propertiesWithKey(String base64Key) {
		NowPlayingProperties properties = new NowPlayingProperties();
		properties.setTokenEncryptionKey(base64Key);
		return properties;
	}

	@Test
	void encryptThenDecrypt_roundTrips() {
		TokenEncryptor encryptor = new TokenEncryptor(
				propertiesWithKey(Base64.getEncoder().encodeToString("dev-only-token-encryption-key-32".getBytes())));

		String encrypted = encryptor.encrypt("secret-oauth-token");

		assertThat(encrypted).isNotEqualTo("secret-oauth-token");
		assertThat(encryptor.decrypt(encrypted)).isEqualTo("secret-oauth-token");
	}

	@Test
	void encrypt_producesDifferentCiphertextEachTime() {
		TokenEncryptor encryptor = new TokenEncryptor(
				propertiesWithKey(Base64.getEncoder().encodeToString("dev-only-token-encryption-key-32".getBytes())));

		assertThat(encryptor.encrypt("token")).isNotEqualTo(encryptor.encrypt("token"));
	}

	@Test
	void constructor_rejectsKeyOfWrongLength() {
		assertThatThrownBy(
				() -> new TokenEncryptor(propertiesWithKey(Base64.getEncoder().encodeToString("too-short".getBytes()))))
				.isInstanceOf(IllegalStateException.class);
	}
}
