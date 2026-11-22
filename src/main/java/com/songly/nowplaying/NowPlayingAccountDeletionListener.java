package com.songly.nowplaying;

import com.songly.identity.UserAccountDeletionRequestedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class NowPlayingAccountDeletionListener {

	private final NowPlayingService nowPlayingService;
	private final NowPlayingStreamHub streamHub;

	NowPlayingAccountDeletionListener(NowPlayingService nowPlayingService, NowPlayingStreamHub streamHub) {
		this.nowPlayingService = nowPlayingService;
		this.streamHub = streamHub;
	}

	@EventListener
	void onAccountDeletionRequested(UserAccountDeletionRequestedEvent event) {
		nowPlayingService.deleteAccountData(event.userId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onAccountDeleted(UserAccountDeletionRequestedEvent event) {
		streamHub.closeAll(event.userId());
	}
}
