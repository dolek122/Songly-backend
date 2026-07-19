package com.songly.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "communities")
class Community {

	@Id
	private UUID id;

	@Column(nullable = false, length = 100, unique = true)
	private String name;

	@Column(length = 500)
	private String description;

	@Column(name = "owner_id", nullable = false)
	private UUID ownerId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Community() {
	}

	Community(UUID id, String name, String description, UUID ownerId, Instant createdAt) {
		this.id = id;
		this.name = name;
		this.description = description;
		this.ownerId = ownerId;
		this.createdAt = createdAt;
	}

	void update(String name, String description) {
		this.name = name;
		this.description = description;
	}

	UUID getId() {
		return id;
	}

	String getName() {
		return name;
	}

	String getDescription() {
		return description;
	}

	UUID getOwnerId() {
		return ownerId;
	}

	Instant getCreatedAt() {
		return createdAt;
	}
}
