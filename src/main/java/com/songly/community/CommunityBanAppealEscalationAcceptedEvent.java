package com.songly.community;

import java.time.Instant;
import java.util.UUID;

public record CommunityBanAppealEscalationAcceptedEvent(UUID communityId, UUID userId, UUID adminId,
		Instant occurredAt) {
}
