package com.songly.nowplaying;

import com.songly.identity.UserAccountDeletionRequestedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Zwykły {@code @EventListener} celowo — patrz
 * {@link UserAccountDeletionRequestedEvent}.
 */
@Component
class NowPlayingAccountDeletionListener {

	private final NowPlayingService nowPlayingService;

	NowPlayingAccountDeletionListener(NowPlayingService nowPlayingService) {
		this.nowPlayingService = nowPlayingService;
	}

	@EventListener
	void onAccountDeletionRequested(UserAccountDeletionRequestedEvent event) {
		nowPlayingService.deleteAccountData(event.userId());
	}
}
