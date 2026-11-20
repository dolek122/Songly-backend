package com.songly.nowplaying;

import com.songly.identity.UserAccountDeletionRequestedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Wiersze kasuje zwykły {@code @EventListener}, celowo — patrz
 * {@link UserAccountDeletionRequestedEvent}. Otwarte strumienie zamyka dopiero
 * commit.
 */
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

	/**
	 * Otwarte strumienie zamykamy dopiero po commicie: usunięcie, które się wycofa,
	 * nie może rozłączyć konta, które dalej istnieje. Nowego strumienia token
	 * usuniętego konta już nie otworzy (ADR 0052). Zamykamy tylko na tej instancji,
	 * a na Renderze jest jedna.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onAccountDeleted(UserAccountDeletionRequestedEvent event) {
		streamHub.closeAll(event.userId());
	}
}
