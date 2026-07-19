package com.songly.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code resourceId} wskazuje obiekt źródłowy (post albo zaproszenie) — celowo
 * bez klucza obcego, bo tabele należą do innych modułów; powiadomienie może
 * przeżyć usunięty post.
 */
@Entity
@Table(name = "notifications")
class Notification {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private NotificationType type;

	@Column(name = "actor_id", nullable = false)
	private UUID actorId;

	@Column(name = "resource_id", nullable = false)
	private UUID resourceId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "read_at")
	private Instant readAt;

	protected Notification() {
	}

	Notification(UUID id, UUID userId, NotificationType type, UUID actorId, UUID resourceId, Instant createdAt) {
		this.id = id;
		this.userId = userId;
		this.type = type;
		this.actorId = actorId;
		this.resourceId = resourceId;
		this.createdAt = createdAt;
	}

	void markRead(Instant readAt) {
		if (this.readAt == null) {
			this.readAt = readAt;
		}
	}

	UUID getId() {
		return id;
	}

	UUID getUserId() {
		return userId;
	}

	NotificationType getType() {
		return type;
	}

	UUID getActorId() {
		return actorId;
	}

	UUID getResourceId() {
		return resourceId;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	Instant getReadAt() {
		return readAt;
	}
}
