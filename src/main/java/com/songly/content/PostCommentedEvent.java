package com.songly.content;

import java.time.Instant;
import java.util.UUID;

public record PostCommentedEvent(UUID postId, UUID postAuthorId, UUID commentId, UUID commentAuthorId,
		Instant occurredAt) {
}
