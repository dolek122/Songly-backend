package com.songly.identity;

import java.time.Instant;
import java.util.UUID;

public record UserSuspensionLiftedEvent(UUID userId, UUID actorId, Instant occurredAt) {
}
