package com.songly.community.dto;

import com.songly.community.BanAppealStatus;
import java.time.Instant;
import java.util.UUID;

public record CommunityBanResponse(UUID userId, String displayName, String avatarUrl, String reason, UUID bannedBy,
		Instant createdAt, Instant expiresAt, String appealText, Instant appealedAt, BanAppealStatus appealStatus) {
}
