package com.songly.content;

import com.songly.community.CommunityDeletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class CommunityDeletedListener {

	private final ContentService contentService;

	CommunityDeletedListener(ContentService contentService) {
		this.contentService = contentService;
	}

	@EventListener
	void onCommunityDeleted(CommunityDeletedEvent event) {
		contentService.deleteCommunityData(event.communityId());
	}
}
