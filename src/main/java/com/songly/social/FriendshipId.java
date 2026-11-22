package com.songly.social;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

class FriendshipId implements Serializable {

	private UUID userAId;
	private UUID userBId;

	protected FriendshipId() {
	}

	private FriendshipId(UUID userAId, UUID userBId) {
		this.userAId = userAId;
		this.userBId = userBId;
	}

	static FriendshipId of(UUID first, UUID second) {
		return first.toString().compareTo(second.toString()) < 0
				? new FriendshipId(first, second)
				: new FriendshipId(second, first);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof FriendshipId other)) {
			return false;
		}
		return Objects.equals(userAId, other.userAId) && Objects.equals(userBId, other.userBId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(userAId, userBId);
	}
}
