package com.songly.community;

import java.time.Instant;
import java.util.UUID;

public record CommunityMemberJoinedEvent(UUID communityId, UUID ownerId, UUID memberId, Instant occurredAt) {
}
