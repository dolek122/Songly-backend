package com.songly.content;

import java.time.Instant;
import java.util.UUID;

public record PostReportedEvent(UUID communityId, UUID postId, UUID reporterId, Instant occurredAt) {
}
