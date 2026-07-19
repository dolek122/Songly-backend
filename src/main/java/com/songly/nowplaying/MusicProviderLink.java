package com.songly.nowplaying;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Połączenie konta użytkownika z zewnętrznym serwisem muzycznym. Tokeny OAuth
 * trzymane wyłącznie zaszyfrowane (AES-GCM) — patrz Claude.md §9.
 */
@Entity
@Table(name = "music_provider_links")
class MusicProviderLink {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(nullable = false, length = 30)
	private String provider;

	@Column(name = "access_token_encrypted", nullable = false)
	private String accessTokenEncrypted;

	@Column(name = "refresh_token_encrypted", nullable = false)
	private String refreshTokenEncrypted;

	@Column(name = "access_token_expires_at", nullable = false)
	private Instant accessTokenExpiresAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected MusicProviderLink() {
	}

	MusicProviderLink(UUID id, UUID userId, String provider, String accessTokenEncrypted, String refreshTokenEncrypted,
			Instant accessTokenExpiresAt, Instant now) {
		this.id = id;
		this.userId = userId;
		this.provider = provider;
		this.accessTokenEncrypted = accessTokenEncrypted;
		this.refreshTokenEncrypted = refreshTokenEncrypted;
		this.accessTokenExpiresAt = accessTokenExpiresAt;
		this.createdAt = now;
		this.updatedAt = now;
	}

	void updateTokens(String accessTokenEncrypted, String refreshTokenEncrypted, Instant accessTokenExpiresAt) {
		this.accessTokenEncrypted = accessTokenEncrypted;
		this.refreshTokenEncrypted = refreshTokenEncrypted;
		this.accessTokenExpiresAt = accessTokenExpiresAt;
		this.updatedAt = Instant.now();
	}

	UUID getUserId() {
		return userId;
	}

	String getProvider() {
		return provider;
	}

	String getAccessTokenEncrypted() {
		return accessTokenEncrypted;
	}

	String getRefreshTokenEncrypted() {
		return refreshTokenEncrypted;
	}

	boolean isAccessTokenExpired(Instant now) {
		return !now.isBefore(accessTokenExpiresAt);
	}
}
