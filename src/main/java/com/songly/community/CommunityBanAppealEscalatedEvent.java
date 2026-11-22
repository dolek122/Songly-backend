package com.songly.community;

import java.time.Instant;
import java.util.UUID;

public record CommunityBanAppealEscalatedEvent(UUID communityId, UUID userId, Instant occurredAt) {
}
