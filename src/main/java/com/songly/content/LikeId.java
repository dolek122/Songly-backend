package com.songly.content;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

class LikeId implements Serializable {

	private UUID postId;
	private UUID userId;

	protected LikeId() {
	}

	LikeId(UUID postId, UUID userId) {
		this.postId = postId;
		this.userId = userId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof LikeId other)) {
			return false;
		}
		return Objects.equals(postId, other.postId) && Objects.equals(userId, other.userId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(postId, userId);
	}
}
