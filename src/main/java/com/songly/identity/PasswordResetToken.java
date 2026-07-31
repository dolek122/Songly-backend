package com.songly.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Jednorazowy token resetu hasła, przechowywany wyłącznie jako skrót SHA-256 —
 * tak samo jak refresh token w {@link Session}.
 *
 * <p>
 * Encja celowo nie ma pola „zużyty". Udany reset kasuje wszystkie tokeny
 * użytkownika, więc ponowne kliknięcie w ten sam link po prostu nie znajduje
 * wiersza i dostaje tę samą odpowiedź co token zmyślony. Kolumna
 * {@code used_at} dawałaby to samo zachowanie, ale zostawiałaby wiersze, które
 * trzeba by potem sprzątać osobnym zadaniem.
 */
@Entity
@Table(name = "password_reset_tokens")
class PasswordResetToken {

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

	protected PasswordResetToken() {
	}

	PasswordResetToken(UUID id, UUID userId, String tokenHash, Instant createdAt, Instant expiresAt) {
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
