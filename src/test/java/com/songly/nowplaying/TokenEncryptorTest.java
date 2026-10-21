package com.songly.nowplaying;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class TokenEncryptorTest {

	private static final String DEV_KEY = Base64.getEncoder()
			.encodeToString("dev-only-token-encryption-key-32".getBytes());

	private static final String REAL_KEY = Base64.getEncoder()
			.encodeToString("0123456789abcdef0123456789abcdef".getBytes());

	private static NowPlayingProperties propertiesWithKey(String base64Key) {
		NowPlayingProperties properties = new NowPlayingProperties();
		properties.setTokenEncryptionKey(base64Key);
		return properties;
	}

	@Test
	void encryptThenDecrypt_roundTrips() {
		TokenEncryptor encryptor = new TokenEncryptor(propertiesWithKey(DEV_KEY), false);

		String encrypted = encryptor.encrypt("secret-oauth-token");

		assertThat(encrypted).isNotEqualTo("secret-oauth-token");
		assertThat(encryptor.decrypt(encrypted)).isEqualTo("secret-oauth-token");
	}

	@Test
	void encrypt_producesDifferentCiphertextEachTime() {
		TokenEncryptor encryptor = new TokenEncryptor(propertiesWithKey(DEV_KEY), false);

		assertThat(encryptor.encrypt("token")).isNotEqualTo(encryptor.encrypt("token"));
	}

	@Test
	void constructor_rejectsKeyOfWrongLength() {
		assertThatThrownBy(
				() -> new TokenEncryptor(propertiesWithKey(Base64.getEncoder().encodeToString("too-short".getBytes())),
						false))
				.isInstanceOf(IllegalStateException.class);
	}

	/**
	 * Klucz z repo jest publiczny — z nim zrzut bazy oddaje tokeny Spotify
	 * wszystkich użytkowników. Środowisko z prawdziwymi sekretami nie może z nim
	 * wstać.
	 */
	@Test
	void constructor_rejectsDevKey_whereProductionSecretsAreRequired() {
		assertThatThrownBy(() -> new TokenEncryptor(propertiesWithKey(DEV_KEY), true))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("NOW_PLAYING_TOKEN_KEY");
	}

	@Test
	void constructor_acceptsRealKey_whereProductionSecretsAreRequired() {
		TokenEncryptor encryptor = new TokenEncryptor(propertiesWithKey(REAL_KEY), true);

		assertThat(encryptor.decrypt(encryptor.encrypt("token"))).isEqualTo("token");
	}
}
