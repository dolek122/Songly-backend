package com.songly.moderationlog;

import com.songly.community.CommunityDeletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Usuwa historię znikającej społeczności. Zwykły {@code @EventListener} z tego
 * samego powodu co w module content: {@code community_id} ma klucz obcy do
 * {@code communities}, więc sprzątanie musi zdążyć przed {@code DELETE} (§8).
 *
 * <p>
 * Kasujemy, a nie zerujemy {@code community_id}: wpis bez społeczności nie ma
 * jak zostać odczytany (oba endpointy pytają albo o społeczność, albo o
 * platformę), więc zostawiłby tabelę rosnącą o wiersze, których nikt nigdy nie
 * zobaczy.
 *
 * <p>
 * Nazwa klasy <b>nie</b> może brzmieć {@code CommunityDeletedListener}, mimo że
 * to by opisywało ją najlepiej: taka klasa jest już w module content, a Spring
 * wyprowadza nazwę beana z prostej nazwy klasy — dwa
 * {@code communityDeletedListener} wywracają start kontekstu (a w testach
 * objawia się to jako niepowiązany „ApplicationContext failure threshold
 * exceeded" na losowym teście).
 */
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
