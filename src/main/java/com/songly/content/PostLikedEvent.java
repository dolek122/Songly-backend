package com.songly.content;

import java.time.Instant;
import java.util.UUID;

/** Publikowane po zapisaniu nowego polubienia (nie przy ponownym PUT). */
public record PostLikedEvent(UUID postId, UUID postAuthorId, UUID likerId, Instant occurredAt) {
}
