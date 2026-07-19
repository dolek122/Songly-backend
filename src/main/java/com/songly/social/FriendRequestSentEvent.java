package com.songly.social;

import java.time.Instant;
import java.util.UUID;

public record FriendRequestSentEvent(UUID requestId, UUID requesterId, UUID addresseeId, Instant occurredAt) {
}
