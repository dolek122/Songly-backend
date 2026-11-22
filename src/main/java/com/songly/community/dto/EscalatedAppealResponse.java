package com.songly.community.dto;

import java.time.Instant;
import java.util.UUID;

public record EscalatedAppealResponse(UUID communityId, String communityName, UUID userId, String reason,
		Instant bannedAt, Instant expiresAt, String appealText, Instant appealedAt, Instant escalatedAt) {
}
