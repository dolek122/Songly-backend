package com.songly.content;

import com.songly.common.storage.ObjectStorage;
import com.songly.common.storage.ObjectStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Kasuje pliki zdjęć, do których nic już nie prowadzi — po commicie transakcji,
 * która je osierociła.
 *
 * <p>
 * Jedno miejsce na wszystkie cztery powody osierocenia: usunięcie posta przez
 * autora, usunięcie go przez właściciela społeczności, skasowanie całej
 * społeczności i skasowanie konta.
 */
@Component
class OrphanedPostImageCleaner {

	private static final Logger log = LoggerFactory.getLogger(OrphanedPostImageCleaner.class);

	private final ObjectStorage objectStorage;

	OrphanedPostImageCleaner(ObjectStorage objectStorage) {
		this.objectStorage = objectStorage;
	}

	/**
	 * Po commicie nie ma już czego wycofać, więc błąd magazynu zostaje w logu: plik
	 * zostaje osierocony, a to jest strona pomyłki, którą wybraliśmy (Claude.md
	 * §9).
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostImageOrphaned(OrphanedPostImageEvent event) {
		try {
			objectStorage.delete(event.key());
		} catch (ObjectStorageException ex) {
			log.warn("Nie udało się skasować zdjęcia posta {} — zostaje osierocone: {}", event.key(), ex.getCause());
		}
	}
}
