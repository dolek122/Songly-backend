package com.songly.moderationlog;

import com.songly.community.CommunityDeletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class ModerationLogCommunityDeletionListener {

	private final ModerationLogService moderationLogService;

	ModerationLogCommunityDeletionListener(ModerationLogService moderationLogService) {
		this.moderationLogService = moderationLogService;
	}

	@EventListener
	void onCommunityDeleted(CommunityDeletedEvent event) {
		moderationLogService.deleteCommunityData(event.communityId());
	}
}
