package com.songly.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Urządzenie odbierające pushe (ADR 0040). Kluczem jest token FCM. */
@Entity
@Table(name = "push_devices")
class PushDevice {

	@Id
	private String token;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PushPlatform platform;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected PushDevice() {
	}

	PushDevice(String token, UUID userId, PushPlatform platform, Instant now) {
		this.token = token;
		this.userId = userId;
		this.platform = platform;
		this.updatedAt = now;
	}

	String getToken() {
		return token;
	}

	UUID getUserId() {
		return userId;
	}

	/** Ten sam telefon, być może inne konto — patrz komentarz w migracji V28. */
	void assignTo(UUID userId, PushPlatform platform, Instant now) {
		this.userId = userId;
		this.platform = platform;
		this.updatedAt = now;
	}
}
