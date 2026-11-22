package com.songly.identity;

import java.time.Instant;
import java.util.UUID;

public record UserSuspensionAppealRejectedEvent(UUID userId, UUID actorId, Instant occurredAt) {
}
