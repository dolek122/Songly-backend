package com.songly.content;

import java.time.Instant;
import java.util.UUID;

public record PostLikedEvent(UUID postId, UUID postAuthorId, UUID likerId, Instant occurredAt) {
}
