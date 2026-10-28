package com.songly.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Token niesie tylko to, czego serwer potrzebuje: identyfikator konta. Adres
 * e-mail w treści wędrował z każdym żądaniem do logów proxy i raportów awarii
 * (Claude.md §13), choć nic go po stronie serwera nie czytało.
 */
class JwtServiceClaimsTest {

	@Test
	void accessToken_carriesTheUserId_andNoEmail() {
		JwtProperties properties = new JwtProperties();
		properties.setSecret("Zq8v3Nf0a7LwXy2Pk5Rt9Bc4Hd6Jm1Qs8Vu0Ge3Ti7Ko");
		properties.setAccessTokenTtl(Duration.ofMinutes(15));
		JwtService jwtService = new JwtService(properties, false);
		jwtService.init();
		UUID userId = UUID.randomUUID();

		String token = jwtService.generateAccessToken(userId);

		String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
		assertThat(payload).doesNotContain("email").doesNotContain("@");
		assertThat(jwtService.parseUserId(token)).isEqualTo(userId);
	}
}
