package com.songly.notification;

import com.songly.identity.UserSessionsRevokedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class PushDeviceRevocationListener {

	private final PushDeviceService pushDeviceService;

	PushDeviceRevocationListener(PushDeviceService pushDeviceService) {
		this.pushDeviceService = pushDeviceService;
	}

	@EventListener
	void onSessionsRevoked(UserSessionsRevokedEvent event) {
		pushDeviceService.deleteAllFor(event.userId());
	}
}
