package com.songly.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_deletion_tokens")
class AccountDeletionToken {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "token_hash", nullable = false, unique = true)
	private String tokenHash;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	protected AccountDeletionToken() {
	}

	AccountDeletionToken(UUID id, UUID userId, String tokenHash, Instant createdAt, Instant expiresAt) {
		this.id = id;
		this.userId = userId;
		this.tokenHash = tokenHash;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	UUID getUserId() {
		return userId;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	boolean isExpired(Instant now) {
		return !now.isBefore(expiresAt);
	}
}
