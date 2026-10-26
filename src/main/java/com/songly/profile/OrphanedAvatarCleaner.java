package com.songly.profile;

import com.songly.common.storage.ObjectStorage;
import com.songly.common.storage.ObjectStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Kasuje pliki avatarów, do których nic już nie prowadzi — po commicie
 * transakcji, która je osierociła (patrz {@link OrphanedAvatarEvent}).
 *
 * <p>
 * Jedno miejsce obsługuje wszystkie trzy powody osierocenia: podmianę avatara,
 * jego usunięcie i skasowanie konta. Bez tego każdy z nich musiałby sam
 * pamiętać o właściwej fazie transakcji, a pomyłka w którymkolwiek dawałaby ten
 * sam cichy objaw — profil ze zdjęciem, które się nie ładuje.
 */
@Component
class OrphanedAvatarCleaner {

	private static final Logger log = LoggerFactory.getLogger(OrphanedAvatarCleaner.class);

	private final ObjectStorage objectStorage;

	OrphanedAvatarCleaner(ObjectStorage objectStorage) {
		this.objectStorage = objectStorage;
	}

	/**
	 * Po commicie nie ma już czego wycofać, więc błąd magazynu zostaje w logu: plik
	 * zostaje osierocony, a to jest strona pomyłki, którą wybraliśmy (Claude.md
	 * §9).
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onAvatarOrphaned(OrphanedAvatarEvent event) {
		try {
			objectStorage.delete(event.key());
		} catch (ObjectStorageException ex) {
			log.warn("Nie udało się skasować avatara {} — zostaje osierocony: {}", event.key(), ex.getCause());
		}
	}
}
