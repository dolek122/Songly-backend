package com.songly.community.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code bannedBy} bywa {@code null} — moderator, który ban nałożył, mógł
 * skasować konto. Ban należy do społeczności, więc przeżywa swojego autora.
 */
public record CommunityBanResponse(UUID userId, String displayName, String avatarUrl, String reason, UUID bannedBy,
		Instant createdAt) {
}
