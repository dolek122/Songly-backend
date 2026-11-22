package com.songly.content;

import java.time.Instant;
import java.util.UUID;

public record CommunityCommentRemovedEvent(UUID communityId, UUID postId, UUID commentAuthorId, UUID moderatorId,
		Instant occurredAt) {
}
