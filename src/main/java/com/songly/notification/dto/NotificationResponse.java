package com.songly.notification.dto;

import com.songly.notification.NotificationType;
import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(UUID id, NotificationType type, UUID actorId, String actorDisplayName,
		String actorAvatarUrl, UUID resourceId, Instant createdAt, Instant readAt) {
}
