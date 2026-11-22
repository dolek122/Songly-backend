package com.songly.community.dto;

import com.songly.community.CommunityRole;
import java.time.Instant;
import java.util.UUID;

public record CommunityResponse(UUID id, String name, String description, UUID ownerId, String ownerDisplayName,
		String ownerAvatarUrl, long memberCount, CommunityRole myRole, Instant createdAt) {
}
