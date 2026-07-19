package com.songly.identity.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.security.jwt")
class JwtProperties {

	private String secret;
	private Duration accessTokenTtl = Duration.ofMinutes(15);
	private Duration refreshTokenTtl = Duration.ofDays(30);

	String getSecret() {
		return secret;
	}

	void setSecret(String secret) {
		this.secret = secret;
	}

	Duration getAccessTokenTtl() {
		return accessTokenTtl;
	}

	void setAccessTokenTtl(Duration accessTokenTtl) {
		this.accessTokenTtl = accessTokenTtl;
	}

	Duration getRefreshTokenTtl() {
		return refreshTokenTtl;
	}

	void setRefreshTokenTtl(Duration refreshTokenTtl) {
		this.refreshTokenTtl = refreshTokenTtl;
	}
}
