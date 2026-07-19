package com.songly.social;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "friendships")
@IdClass(FriendshipId.class)
class Friendship {

	@Id
	@Column(name = "user_a_id")
	private UUID userAId;

	@Id
	@Column(name = "user_b_id")
	private UUID userBId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Friendship() {
	}

	private Friendship(UUID userAId, UUID userBId, Instant createdAt) {
		this.userAId = userAId;
		this.userBId = userBId;
		this.createdAt = createdAt;
	}

	/** Zawsze przez porządek kanoniczny z {@link FriendshipId#of}. */
	static Friendship between(UUID first, UUID second, Instant createdAt) {
		return first.toString().compareTo(second.toString()) < 0 ? new Friendship(first, second, createdAt)
				: new Friendship(second, first, createdAt);
	}

	UUID getUserAId() {
		return userAId;
	}

	UUID getUserBId() {
		return userBId;
	}

	/** Drugi koniec znajomości z perspektywy {@code userId}. */
	UUID otherThan(UUID userId) {
		return userAId.equals(userId) ? userBId : userAId;
	}

	Instant getCreatedAt() {
		return createdAt;
	}
}
