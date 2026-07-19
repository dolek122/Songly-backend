package com.songly.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "posts")
class Post {

	@Id
	private UUID id;

	@Column(name = "author_id", nullable = false)
	private UUID authorId;

	@Column(nullable = false, length = 1000)
	private String content;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Post() {
	}

	Post(UUID id, UUID authorId, String content, Instant createdAt) {
		this.id = id;
		this.authorId = authorId;
		this.content = content;
		this.createdAt = createdAt;
	}

	UUID getId() {
		return id;
	}

	UUID getAuthorId() {
		return authorId;
	}

	String getContent() {
		return content;
	}

	Instant getCreatedAt() {
		return createdAt;
	}
}
