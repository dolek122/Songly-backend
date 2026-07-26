package com.songly.content;

import com.songly.community.CommunityDeletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Usuwa posty ze znikającej społeczności.
 *
 * <p>
 * Zwykły {@code @EventListener}, <b>nie</b>
 * {@code @TransactionalEventListener(AFTER_COMMIT)} — dokładnie ta sama
 * pułapka, co przy listenerach usuwania konta (Claude.md §9).
 * {@code posts.community_id} ma klucz obcy do {@code communities}, więc
 * sprzątanie musi wykonać się synchronicznie, w tej samej transakcji,
 * <i>przed</i> usunięciem wiersza społeczności — inaczej baza odrzuci
 * {@code DELETE}. Dzięki temu całość commituje się albo rolluje razem.
 */
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
