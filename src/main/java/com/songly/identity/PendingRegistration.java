package com.songly.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pending_registrations")
class PendingRegistration {

	@Id
	private UUID id;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "token_hash", nullable = false, unique = true)
	private String tokenHash;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	protected PendingRegistration() {
	}

	PendingRegistration(UUID id, String email, String passwordHash, String tokenHash, Instant createdAt,
			Instant expiresAt) {
		this.id = id;
		this.email = email;
		this.passwordHash = passwordHash;
		this.tokenHash = tokenHash;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	String getEmail() {
		return email;
	}

	String getPasswordHash() {
		return passwordHash;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	boolean isExpired(Instant now) {
		return !now.isBefore(expiresAt);
	}

	void reissue(String newTokenHash, Instant now, Instant newExpiresAt) {
		this.tokenHash = newTokenHash;
		this.createdAt = now;
		this.expiresAt = newExpiresAt;
	}
}
