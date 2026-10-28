package com.songly.profile;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.songly.common.storage.ObjectStorage;
import com.songly.common.storage.ObjectStorageException;
import org.junit.jupiter.api.Test;

/**
 * Po commicie nie ma czego wycofać, więc błąd magazynu (od ADR 0049 zgłaszany
 * wyjątkiem) zostaje w logu, a nie wychodzi z listenera.
 */
class OrphanedAvatarCleanerTest {

	@Test
	void storageFailure_isLoggedInsteadOfThrown() {
		ObjectStorage objectStorage = mock(ObjectStorage.class);
		doThrow(new ObjectStorageException("magazyn nie odpowiada", null)).when(objectStorage).delete("avatars/a.png");
		OrphanedAvatarCleaner cleaner = new OrphanedAvatarCleaner(objectStorage);

		assertThatCode(() -> cleaner.onAvatarOrphaned(new OrphanedAvatarEvent("avatars/a.png")))
				.doesNotThrowAnyException();
	}
}
