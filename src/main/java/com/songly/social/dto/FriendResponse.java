package com.songly.social.dto;

import java.time.Instant;
import java.util.UUID;

public record FriendResponse(UUID userId, String displayName, String avatarUrl, Instant friendsSince) {
}
