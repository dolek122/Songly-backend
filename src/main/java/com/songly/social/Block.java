package com.songly.social;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "blocks")
@IdClass(BlockId.class)
class Block {

	@Id
	@Column(name = "blocker_id")
	private UUID blockerId;

	@Id
	@Column(name = "blocked_id")
	private UUID blockedId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Block() {
	}

	Block(UUID blockerId, UUID blockedId, Instant createdAt) {
		this.blockerId = blockerId;
		this.blockedId = blockedId;
		this.createdAt = createdAt;
	}

	UUID getBlockerId() {
		return blockerId;
	}

	UUID getBlockedId() {
		return blockedId;
	}

	Instant getCreatedAt() {
		return createdAt;
	}
}
