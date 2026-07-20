package com.songly.notification;

import com.songly.identity.UserAccountDeletionRequestedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Zwykły {@code @EventListener} celowo — patrz
 * {@link UserAccountDeletionRequestedEvent}.
 */
@Component
class NotificationAccountDeletionListener {

	private final NotificationService notificationService;

	NotificationAccountDeletionListener(NotificationService notificationService) {
		this.notificationService = notificationService;
	}

	@EventListener
	void onAccountDeletionRequested(UserAccountDeletionRequestedEvent event) {
		notificationService.deleteAccountData(event.userId());
	}
}
