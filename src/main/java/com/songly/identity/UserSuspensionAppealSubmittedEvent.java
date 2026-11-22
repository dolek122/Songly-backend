package com.songly.identity;

import java.time.Instant;
import java.util.UUID;

public record UserSuspensionAppealSubmittedEvent(UUID userId, Instant occurredAt) {
}
