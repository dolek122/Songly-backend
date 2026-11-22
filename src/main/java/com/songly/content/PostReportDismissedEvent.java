package com.songly.content;

import java.time.Instant;
import java.util.UUID;

public record PostReportDismissedEvent(UUID communityId, UUID postId, UUID reporterId, UUID moderatorId,
		Instant occurredAt) {
}
