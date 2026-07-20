package com.songly.content;

import java.time.Instant;
import java.util.UUID;

/** Publikowane po zapisaniu nowego udostępnienia (nie przy ponownym PUT). */
public record PostSharedEvent(UUID postId, UUID postAuthorId, UUID sharerId, Instant occurredAt) {
}
