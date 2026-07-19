package com.songly.social.dto;

import java.time.Instant;
import java.util.UUID;

public record BlockedUserResponse(UUID userId, String displayName, Instant blockedAt) {
}
