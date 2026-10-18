package com.songly.notification;

import com.songly.identity.UserSessionsRevokedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Urządzenia push giną razem z sesjami (ADR 0040). Zmiana hasła po kradzieży
 * telefonu, która zostawia na nim pushe z nazwami znajomych, nie zamyka niczego
 * — dokładnie ten sam argument, przez który zmiana hasła odwołuje wszystkie
 * sesje (Claude.md §9).
 *
 * <p>
 * Zwykły {@code @EventListener}: odpięcie zatwierdza się albo wycofuje razem z
 * rewokacją, także w ścieżce wykrycia kradzieży tokenu, która commituje mimo
 * wyjątku ({@code noRollbackFor}).
 */
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
