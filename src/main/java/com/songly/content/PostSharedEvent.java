package com.songly.content;

import java.time.Instant;
import java.util.UUID;

public record PostSharedEvent(UUID postId, UUID postAuthorId, UUID sharerId, Instant occurredAt) {
}
