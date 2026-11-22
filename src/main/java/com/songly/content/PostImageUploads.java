package com.songly.content;

import com.songly.common.media.SanitizedImage;
import com.songly.common.storage.ObjectStorage;
import com.songly.content.dto.PostImageResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@EnableConfigurationProperties(PostImageProperties.class)
class PostImageUploads {

	private static final String KEY_PREFIX = "posts/";

	private final PostImageUploadRepository repository;
	private final ObjectStorage objectStorage;
	private final ApplicationEventPublisher eventPublisher;
	private final PostImageProperties properties;

	PostImageUploads(PostImageUploadRepository repository, ObjectStorage objectStorage,
			ApplicationEventPublisher eventPublisher, PostImageProperties properties) {
		this.repository = repository;
		this.objectStorage = objectStorage;
		this.eventPublisher = eventPublisher;
		this.properties = properties;
	}

	@Transactional
	PostImageResponse upload(UUID ownerId, byte[] content) {
		SanitizedImage image = SanitizedImage.from(content);
		String key = "%s%s/%s.%s".formatted(KEY_PREFIX, ownerId, UUID.randomUUID(), image.type().extension());
		objectStorage.put(key, image.content(), image.type().contentType());
		repository.save(new PostImageUpload(key, ownerId, Instant.now()));

		List<String> pending = repository.findKeysByOwnerIdNewestFirst(ownerId);
		for (String evicted : pending.subList(Math.min(properties.getMaxPending(), pending.size()), pending.size())) {
			if (repository.deleteByImageKey(evicted) == 1) {
				eventPublisher.publishEvent(new OrphanedPostImageEvent(evicted));
			}
		}
		return new PostImageResponse(key, objectStorage.publicUrl(key));
	}

	@Transactional(propagation = Propagation.MANDATORY)
	String claim(UUID ownerId, String imageKey) {
		if (imageKey == null || imageKey.isBlank()) {
			return null;
		}
		if (repository.deleteByImageKeyAndOwnerId(imageKey, ownerId) == 0) {
			throw new UnknownPostImageException();
		}
		return imageKey;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	void deleteAllOf(UUID ownerId) {
		List<String> keys = repository.findKeysByOwnerIdNewestFirst(ownerId);
		repository.deleteByOwnerId(ownerId);
		keys.forEach(key -> eventPublisher.publishEvent(new OrphanedPostImageEvent(key)));
	}

	int removeAbandoned(Instant now) {
		Instant cutoff = now.minus(properties.getPendingTtl());
		int batchSize = properties.getCleanupBatchSize();
		int removed = 0;
		List<String> keys;
		do {
			keys = repository.findKeysCreatedBefore(cutoff, Limit.of(batchSize));
			for (String key : keys) {
				if (repository.deleteByImageKey(key) == 1) {
					objectStorage.delete(key);
					removed++;
				}
			}
		} while (keys.size() == batchSize);
		return removed;
	}
}
