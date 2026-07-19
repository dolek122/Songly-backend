package com.songly.community;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

class CommunityMemberId implements Serializable {

	private UUID communityId;
	private UUID userId;

	protected CommunityMemberId() {
	}

	CommunityMemberId(UUID communityId, UUID userId) {
		this.communityId = communityId;
		this.userId = userId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof CommunityMemberId other)) {
			return false;
		}
		return Objects.equals(communityId, other.communityId) && Objects.equals(userId, other.userId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(communityId, userId);
	}
}
