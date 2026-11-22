package com.songly.identity;

import java.time.Instant;
import java.util.UUID;

public record AdminRoleChangedEvent(UUID userId, UUID actorId, boolean granted, Instant occurredAt) {
}
