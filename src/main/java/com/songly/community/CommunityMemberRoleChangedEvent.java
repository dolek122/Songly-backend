package com.songly.community;

import java.time.Instant;
import java.util.UUID;

public record CommunityMemberRoleChangedEvent(UUID communityId, UUID userId, UUID changedBy, boolean moderationGranted,
		Instant occurredAt) {
}
