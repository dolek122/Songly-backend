package com.songly.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shares")
@IdClass(ShareId.class)
class Share {

	@Id
	@Column(name = "post_id")
	private UUID postId;

	@Id
	@Column(name = "user_id")
	private UUID userId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Share() {
	}

	Share(UUID postId, UUID userId, Instant createdAt) {
		this.postId = postId;
		this.userId = userId;
		this.createdAt = createdAt;
	}

	UUID getPostId() {
		return postId;
	}

	UUID getUserId() {
		return userId;
	}
}
