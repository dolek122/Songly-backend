package com.songly.notification;

import java.util.UUID;

record NotificationCreatedEvent(UUID notificationId, UUID recipientId, UUID actorId, NotificationType type,
		UUID resourceId) {
}
