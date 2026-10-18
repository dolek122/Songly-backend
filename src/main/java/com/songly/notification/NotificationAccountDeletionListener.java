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
	private final PushDeviceService pushDeviceService;

	NotificationAccountDeletionListener(NotificationService notificationService,
			NotificationMailPreferenceService mailPreferenceService, PushDeviceService pushDeviceService) {
		this.notificationService = notificationService;
		this.mailPreferenceService = mailPreferenceService;
		this.pushDeviceService = pushDeviceService;
	}

	/**
	 * Trzy tabele tego modułu mają FK do {@code users} — powiadomienia, preferencje
	 * poczty (ADR 0020) i urządzenia push (ADR 0040) — więc wszystkie muszą zniknąć
	 * przed {@code DELETE} na użytkowniku.
	 */
	@EventListener
	void onAccountDeletionRequested(UserAccountDeletionRequestedEvent event) {
		notificationService.deleteAccountData(event.userId());
		mailPreferenceService.deleteAccountData(event.userId());
		pushDeviceService.deleteAllFor(event.userId());
	}
}
