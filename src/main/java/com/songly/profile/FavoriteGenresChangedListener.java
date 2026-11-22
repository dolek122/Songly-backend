package com.songly.profile;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class FavoriteGenresChangedListener {

	private final GenrePopularityCache cache;

	FavoriteGenresChangedListener(GenrePopularityCache cache) {
		this.cache = cache;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onFavoriteGenresChanged(FavoriteGenresChangedEvent event) {
		cache.evict();
	}
}
