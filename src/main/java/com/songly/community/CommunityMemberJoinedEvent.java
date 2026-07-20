package com.songly.community;

import java.time.Instant;
import java.util.UUID;

/** Publikowane po zapisaniu nowego członkostwa (nie przy ponownym dołączeniu). */
public record CommunityMemberJoinedEvent(UUID communityId, UUID ownerId, UUID memberId, Instant occurredAt) {
}
