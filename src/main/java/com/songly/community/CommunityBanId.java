package com.songly.community;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

class CommunityBanId implements Serializable {

	private UUID communityId;
	private UUID userId;

	protected CommunityBanId() {
	}

	CommunityBanId(UUID communityId, UUID userId) {
		this.communityId = communityId;
		this.userId = userId;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof CommunityBanId that)) {
			return false;
		}
		return Objects.equals(communityId, that.communityId) && Objects.equals(userId, that.userId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(communityId, userId);
	}
}
