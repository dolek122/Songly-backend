package com.songly.social;

import java.time.Instant;
import java.util.UUID;

/** Publikowane też przy auto-akceptacji wzajemnych zaproszeń. */
public record FriendRequestAcceptedEvent(UUID requestId, UUID requesterId, UUID addresseeId, Instant occurredAt) {
}
