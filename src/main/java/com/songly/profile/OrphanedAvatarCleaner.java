package com.songly.profile;

import com.songly.common.storage.ObjectStorage;
import com.songly.common.storage.ObjectStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class OrphanedAvatarCleaner {

	private static final Logger log = LoggerFactory.getLogger(OrphanedAvatarCleaner.class);

	private final ObjectStorage objectStorage;

	OrphanedAvatarCleaner(ObjectStorage objectStorage) {
		this.objectStorage = objectStorage;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onAvatarOrphaned(OrphanedAvatarEvent event) {
		try {
			objectStorage.delete(event.key());
		} catch (ObjectStorageException ex) {
			log.warn("Nie udało się skasować avatara {} — zostaje osierocony: {}", event.key(), ex.getCause());
		}
	}
}
