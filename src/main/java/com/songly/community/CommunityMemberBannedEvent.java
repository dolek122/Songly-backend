package com.songly.community;

import java.time.Instant;
import java.util.UUID;

public record CommunityMemberBannedEvent(UUID communityId, UUID userId, UUID moderatorId, Instant expiresAt,
		Instant occurredAt) {
}
