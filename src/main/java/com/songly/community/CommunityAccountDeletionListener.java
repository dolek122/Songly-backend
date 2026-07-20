package com.songly.community;

import com.songly.identity.UserAccountDeletionRequestedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Zwykły {@code @EventListener} celowo — patrz
 * {@link UserAccountDeletionRequestedEvent}.
 */
@Component
class CommunityAccountDeletionListener {

	private final CommunityService communityService;

	CommunityAccountDeletionListener(CommunityService communityService) {
		this.communityService = communityService;
	}

	@EventListener
	void onAccountDeletionRequested(UserAccountDeletionRequestedEvent event) {
		communityService.deleteAccountData(event.userId());
	}
}
