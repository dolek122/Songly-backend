package com.songly.profile;

import com.songly.common.storage.ObjectStorage;
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

	private final ObjectStorage objectStorage;

	OrphanedAvatarCleaner(ObjectStorage objectStorage) {
		this.objectStorage = objectStorage;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onAvatarOrphaned(OrphanedAvatarEvent event) {
		objectStorage.delete(event.key());
	}
}
