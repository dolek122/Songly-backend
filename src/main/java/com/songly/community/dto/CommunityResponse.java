package com.songly.community.dto;

import com.songly.community.CommunityRole;
import java.time.Instant;
import java.util.UUID;

/** {@code myRole} jest {@code null}, gdy przeglądający nie jest członkiem. */
public record CommunityResponse(UUID id, String name, String description, UUID ownerId, long memberCount,
		CommunityRole myRole, Instant createdAt) {
}
