package com.songly.identity.security;

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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

@Service
@EnableConfigurationProperties(JwtProperties.class)
public class JwtService {

	private final JwtProperties properties;
	private SecretKey key;

	JwtService(JwtProperties properties) {
		this.properties = properties;
	}

	@PostConstruct
	void init() {
		this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
	}

	public String generateAccessToken(UUID userId, String email) {
		Instant now = Instant.now();
		return Jwts.builder().subject(userId.toString()).claim("email", email).issuedAt(Date.from(now))
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
