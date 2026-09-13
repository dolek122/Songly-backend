package com.songly.profile;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Unieważnia obraz popularności gatunków (ADR 0032).
 *
 * <p>
 * <b>{@code AFTER_COMMIT} jest tu warunkiem poprawności, nie porządkiem.</b>
 * Unieważnienie w trakcie transakcji otwiera okno, w którym równoległe żądanie
 * przelicza obraz, <i>nie widząc</i> jeszcze niezacommitowanych zmian, i
 * zapisuje go ze świeżym TTL — więc zbyt wczesne unieważnienie nie tylko nie
 * pomaga, ale utrwala nieaktualne liczby na pełną godzinę. Skutek cichy: wyniki
 * dopasowań są lekko przesunięte i nic tego nie sygnalizuje.
 *
 * <p>
 * To ta sama rodzina pułapek co kasowanie plików w §9: praca poza transakcją
 * musi czekać na commit, bo dopiero commit mówi, jak jest. Różnica jest w
 * koszcie pomyłki — przedwczesne kasowanie pliku niszczy dane, przedwczesne
 * unieważnienie cache'a tylko po cichu przywraca to, co miało zniknąć.
 */
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
