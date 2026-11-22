package com.songly.moderationlog;

import com.songly.identity.UserAccountDeletionRequestedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class ModerationLogAccountDeletionListener {

	private final ModerationLogService moderationLogService;

	ModerationLogAccountDeletionListener(ModerationLogService moderationLogService) {
		this.moderationLogService = moderationLogService;
	}

	@EventListener
	void onAccountDeletionRequested(UserAccountDeletionRequestedEvent event) {
		moderationLogService.deleteAccountData(event.userId());
	}
}
