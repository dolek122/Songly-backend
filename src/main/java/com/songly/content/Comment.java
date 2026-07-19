package com.songly.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "comments")
class Comment {

	@Id
	private UUID id;

	@Column(name = "post_id", nullable = false)
	private UUID postId;

	@Column(name = "author_id", nullable = false)
	private UUID authorId;

	@Column(nullable = false, length = 500)
	private String content;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Comment() {
	}

	Comment(UUID id, UUID postId, UUID authorId, String content, Instant createdAt) {
		this.id = id;
		this.postId = postId;
		this.authorId = authorId;
		this.content = content;
		this.createdAt = createdAt;
	}

	UUID getId() {
		return id;
	}

	UUID getPostId() {
		return postId;
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
