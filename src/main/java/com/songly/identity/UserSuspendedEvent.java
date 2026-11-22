package com.songly.identity;

import java.time.Instant;
import java.util.UUID;

public record UserSuspendedEvent(UUID userId, UUID actorId, Instant expiresAt, Instant occurredAt) {
}
