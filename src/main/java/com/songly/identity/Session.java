package com.songly.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Refresh-token session, umożliwia rewokację bez czekania na wygaśnięcie
 * tokenu.
 */
@Entity
@Table(name = "sessions")
class Session {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "refresh_token_hash", nullable = false, unique = true)
	private String refreshTokenHash;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(nullable = false)
	private boolean revoked;

	protected Session() {
	}

	Session(UUID id, UUID userId, String refreshTokenHash, Instant createdAt, Instant expiresAt) {
		this.id = id;
		this.userId = userId;
		this.refreshTokenHash = refreshTokenHash;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
		this.revoked = false;
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getRefreshTokenHash() {
		return refreshTokenHash;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public boolean isRevoked() {
		return revoked;
	}

	public boolean isActive(Instant now) {
		return !revoked && now.isBefore(expiresAt);
	}

	public void revoke() {
		this.revoked = true;
	}
}
