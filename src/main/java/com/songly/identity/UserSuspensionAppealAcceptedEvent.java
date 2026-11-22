package com.songly.identity;

import java.time.Instant;
import java.util.UUID;

public record UserSuspensionAppealAcceptedEvent(UUID userId, UUID actorId, Instant occurredAt) {
}
