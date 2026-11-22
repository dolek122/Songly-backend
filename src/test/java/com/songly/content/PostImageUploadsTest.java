package com.songly.content;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.songly.common.storage.ObjectStorage;
import com.songly.common.storage.ObjectStorageException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;

@ExtendWith(MockitoExtension.class)
class PostImageUploadsTest {

	@Mock
	private PostImageUploadRepository repository;

	@Mock
	private ObjectStorage objectStorage;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@Test
	void storageFailure_stopsTheRun_beforeTouchingFurtherRows() {
		PostImageUploads uploads = new PostImageUploads(repository, objectStorage, eventPublisher,
				new PostImageProperties());
		when(repository.findKeysCreatedBefore(any(Instant.class), any(Limit.class)))
				.thenReturn(List.of("posts/a.png", "posts/b.png"));
		when(repository.deleteByImageKey("posts/a.png")).thenReturn(1);
		doThrow(new ObjectStorageException("magazyn nie odpowiada", null)).when(objectStorage).delete("posts/a.png");

		assertThatThrownBy(() -> uploads.removeAbandoned(Instant.now())).isInstanceOf(ObjectStorageException.class);

		verify(repository, never()).deleteByImageKey("posts/b.png");
	}

	@Test
	void orphanCleaner_logsStorageFailure_insteadOfThrowing() {
		OrphanedPostImageCleaner cleaner = new OrphanedPostImageCleaner(objectStorage);
		doThrow(new ObjectStorageException("magazyn nie odpowiada", null)).when(objectStorage).delete("posts/a.png");

		assertThatCode(() -> cleaner.onPostImageOrphaned(new OrphanedPostImageEvent("posts/a.png")))
				.doesNotThrowAnyException();
	}
}
