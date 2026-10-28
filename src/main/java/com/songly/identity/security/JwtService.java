package com.songly.identity.security;

import com.songly.common.config.DevSecrets;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

@Service
@EnableConfigurationProperties(JwtProperties.class)
public class JwtService {

	private final JwtProperties properties;
	private final boolean productionSecretsRequired;
	private SecretKey key;

	JwtService(JwtProperties properties,
			@Value("${" + DevSecrets.PRODUCTION_REQUIRED_PROPERTY + ":false}") boolean productionSecretsRequired) {
		this.properties = properties;
		this.productionSecretsRequired = productionSecretsRequired;
	}

	/**
	 * Sekret deweloperski z repo odpada tam, gdzie wymagane są prawdziwe sekrety: z
	 * nim każdy podpisałby token dowolnego konta, także administratora.
	 */
	@PostConstruct
	void init() {
		DevSecrets.rejectDevValue(productionSecretsRequired, properties.getSecret(), "JWT_SECRET");
		this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Sam identyfikator w {@code sub}, bez adresu e-mail. Serwer go z tokenu nie
	 * czytał, a token ląduje w nagłówkach, logach proxy i raportach awarii klienta
	 * — każdy z nich niósł przy tym adres (Claude.md §13, ADR 0046).
	 */
	public String generateAccessToken(UUID userId) {
		Instant now = Instant.now();
		return Jwts.builder().subject(userId.toString()).issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(properties.getAccessTokenTtl()))).signWith(key).compact();
	}

	public UUID parseUserId(String token) {
		try {
			Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
			return UUID.fromString(claims.getSubject());
		} catch (JwtException | IllegalArgumentException e) {
			throw new InvalidAccessTokenException();
		}
	}

	public Duration getAccessTokenTtl() {
		return properties.getAccessTokenTtl();
	}

	public Duration getRefreshTokenTtl() {
		return properties.getRefreshTokenTtl();
	}
}
