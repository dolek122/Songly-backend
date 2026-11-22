package com.songly.notification;

import com.songly.identity.UserAccountDeletionRequestedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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

	@EventListener
	void onAccountDeletionRequested(UserAccountDeletionRequestedEvent event) {
		notificationService.deleteAccountData(event.userId());
		mailPreferenceService.deleteAccountData(event.userId());
		pushDeviceService.deleteAllFor(event.userId());
	}
}
