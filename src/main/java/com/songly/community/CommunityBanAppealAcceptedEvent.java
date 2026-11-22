package com.songly.community;

import java.time.Instant;
import java.util.UUID;

public record CommunityBanAppealAcceptedEvent(UUID communityId, UUID userId, UUID moderatorId, Instant occurredAt) {
}
