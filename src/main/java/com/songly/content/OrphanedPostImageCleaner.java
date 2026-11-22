package com.songly.content;

import com.songly.common.storage.ObjectStorage;
import com.songly.common.storage.ObjectStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class OrphanedPostImageCleaner {

	private static final Logger log = LoggerFactory.getLogger(OrphanedPostImageCleaner.class);

	private final ObjectStorage objectStorage;

	OrphanedPostImageCleaner(ObjectStorage objectStorage) {
		this.objectStorage = objectStorage;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostImageOrphaned(OrphanedPostImageEvent event) {
		try {
			objectStorage.delete(event.key());
		} catch (ObjectStorageException ex) {
			log.warn("Nie udało się skasować zdjęcia posta {} — zostaje osierocone: {}", event.key(), ex.getCause());
		}
	}
}
