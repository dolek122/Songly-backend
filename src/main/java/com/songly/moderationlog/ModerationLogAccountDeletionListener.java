package com.songly.moderationlog;

import com.songly.identity.UserAccountDeletionRequestedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Zwykły {@code @EventListener}, nie {@code AFTER_COMMIT} — obie kolumny mają
 * klucz obcy do {@code users}, więc sprzątanie musi wykonać się w tej samej
 * transakcji, przed {@code DELETE} (Claude.md §9).
 *
 * <p>
 * Wpisy o <b>tej osobie</b> znikają, wpisy <b>jej autorstwa</b> zostają bez
 * autora — ta sama asymetria co przy banach (ADR 0011): decyzja należy do
 * społeczności, a historia sankcji wobec nieistniejącego konta nie ma czego
 * opisywać.
 */
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
