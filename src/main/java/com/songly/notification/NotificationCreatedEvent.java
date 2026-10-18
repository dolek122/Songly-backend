package com.songly.notification;

import java.util.UUID;

/**
 * Wewnętrzne zdarzenie modułu: w skrzynce powstało <b>nowe</b> powiadomienie.
 * Nie leci przy powiadomieniu pominiętym albo zwiniętym przez deduplikację (ADR
 * 0035), więc push dziedziczy jej bramki za darmo — tak jak mail dziedziczy
 * bramki u publikującego (ADR 0013).
 */
record NotificationCreatedEvent(UUID notificationId, UUID recipientId, UUID actorId, NotificationType type,
		UUID resourceId) {
}
