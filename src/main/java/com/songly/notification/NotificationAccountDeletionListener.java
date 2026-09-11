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
	private final NotificationMailPreferenceService mailPreferenceService;

	NotificationAccountDeletionListener(NotificationService notificationService,
			NotificationMailPreferenceService mailPreferenceService) {
		this.notificationService = notificationService;
		this.mailPreferenceService = mailPreferenceService;
	}

	/**
	 * Dwie tabele tego modułu mają FK do {@code users} — powiadomienia i
	 * preferencje poczty (ADR 0020) — więc obie muszą zniknąć przed {@code DELETE}
	 * na użytkowniku.
	 */
	@EventListener
	void onAccountDeletionRequested(UserAccountDeletionRequestedEvent event) {
		notificationService.deleteAccountData(event.userId());
		mailPreferenceService.deleteAccountData(event.userId());
	}
}
