package com.songly.community;

import java.time.Instant;
import java.util.UUID;

public record CommunityBanAppealSubmittedEvent(UUID communityId, UUID userId, Instant occurredAt) {
}
