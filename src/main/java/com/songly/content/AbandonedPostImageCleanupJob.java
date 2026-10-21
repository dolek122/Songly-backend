package com.songly.content;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Usuwa zdjęcia, które wgrano, ale nigdy nie przypięto do posta (ADR 0048). */
@Component
@ConditionalOnProperty(name = "songly.content.post-image.cleanup-enabled", havingValue = "true", matchIfMissing = true)
class AbandonedPostImageCleanupJob {

	private static final Logger log = LoggerFactory.getLogger(AbandonedPostImageCleanupJob.class);

	private final PostImageUploads uploads;

	AbandonedPostImageCleanupJob(PostImageUploads uploads) {
		this.uploads = uploads;
	}

	@Scheduled(fixedDelayString = "${songly.content.post-image.cleanup-interval:PT1H}")
	void removeAbandonedImages() {
		try {
			int removed = uploads.removeAbandoned(Instant.now());
			if (removed > 0) {
				log.info("Usunięto {} porzuconych zdjęć postów", removed);
			}
		} catch (RuntimeException ex) {
			// Higiena, nie ścieżka krytyczna — tak samo jak sprzątanie sesji.
			log.warn("Sprzątanie porzuconych zdjęć postów nie powiodło się: {}", ex.toString());
		}
	}
}
