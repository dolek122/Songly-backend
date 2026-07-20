package com.songly.content;

import com.songly.identity.UserAccountDeletionRequestedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Zwykły {@code @EventListener} celowo — patrz
 * {@link UserAccountDeletionRequestedEvent}.
 */
@Component
class ContentAccountDeletionListener {

	private final ContentService contentService;

	ContentAccountDeletionListener(ContentService contentService) {
		this.contentService = contentService;
	}

	@EventListener
	void onAccountDeletionRequested(UserAccountDeletionRequestedEvent event) {
		contentService.deleteAccountData(event.userId());
	}
}
