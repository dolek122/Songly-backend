package com.songly.content.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code communityId} jest {@code null} dla zwykłego posta; ustawione znaczy,
 * że post został opublikowany w społeczności i nie pojawia się w feedzie
 * znajomych.
 */
public record PostResponse(UUID id, UUID authorId, String authorDisplayName, String authorAvatarUrl, String content,
		Instant createdAt, UUID communityId, long likeCount, boolean likedByMe, long commentCount, long shareCount,
		boolean sharedByMe) {
}
