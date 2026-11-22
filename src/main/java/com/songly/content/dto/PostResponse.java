package com.songly.content.dto;

import java.time.Instant;
import java.util.UUID;

public record PostResponse(UUID id, UUID authorId, String authorDisplayName, String authorAvatarUrl, String content,
		String imageUrl, Instant createdAt, UUID communityId, long likeCount, boolean likedByMe, long commentCount,
		long shareCount, boolean sharedByMe) {
}
