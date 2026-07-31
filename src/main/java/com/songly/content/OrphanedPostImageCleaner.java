package com.songly.content;

import com.songly.common.storage.ObjectStorage;
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

	private final ObjectStorage objectStorage;

	OrphanedPostImageCleaner(ObjectStorage objectStorage) {
		this.objectStorage = objectStorage;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostImageOrphaned(OrphanedPostImageEvent event) {
		objectStorage.delete(event.key());
	}
}
