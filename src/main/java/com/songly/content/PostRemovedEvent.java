package com.songly.content;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record PostRemovedEvent(UUID postId, UUID postAuthorId, UUID moderatorId, Set<UUID> reporterIds,
		Instant occurredAt) {
}
