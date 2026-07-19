package com.songly.notification.dto;

import com.songly.notification.NotificationType;
import java.time.Instant;
import java.util.UUID;

/** {@code readAt} jest {@code null} dla nieprzeczytanych. */
public record NotificationResponse(UUID id, NotificationType type, UUID actorId, String actorDisplayName,
		UUID resourceId, Instant createdAt, Instant readAt) {
}
