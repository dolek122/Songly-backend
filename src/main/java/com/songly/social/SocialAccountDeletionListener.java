package com.songly.social;

import com.songly.identity.UserAccountDeletionRequestedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Zwykły {@code @EventListener} celowo — patrz
 * {@link UserAccountDeletionRequestedEvent}.
 */
@Component
class SocialAccountDeletionListener {

	private final SocialGraphService socialGraphService;

	SocialAccountDeletionListener(SocialGraphService socialGraphService) {
		this.socialGraphService = socialGraphService;
	}

	@EventListener
	void onAccountDeletionRequested(UserAccountDeletionRequestedEvent event) {
		socialGraphService.deleteAccountData(event.userId());
	}
}
