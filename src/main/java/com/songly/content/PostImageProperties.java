package com.songly.content;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Zdjęcia wgrane, a jeszcze nieprzypięte do posta (ADR 0048). */
@ConfigurationProperties(prefix = "songly.content.post-image")
class PostImageProperties {

	private int maxPending = 10;

	private Duration pendingTtl = Duration.ofHours(24);

	private int cleanupBatchSize = 500;

	/**
	 * Ile zdjęć może czekać na post naraz. Kolejne wgranie usuwa najstarsze
	 * oczekujące, zamiast się nie udać: mobilka wgrywa zdjęcie od razu po wybraniu,
	 * więc kilka porzuconych to zwykłe przeglądanie galerii, nie nadużycie, a
	 * odmowa blokowałaby człowieka na całą dobę.
	 */
	int getMaxPending() {
		return maxPending;
	}

	void setMaxPending(int maxPending) {
		this.maxPending = maxPending;
	}

	/** Po jakim czasie nieprzypięte zdjęcie uznajemy za porzucone. */
	Duration getPendingTtl() {
		return pendingTtl;
	}

	void setPendingTtl(Duration pendingTtl) {
		this.pendingTtl = pendingTtl;
	}

	int getCleanupBatchSize() {
		return cleanupBatchSize;
	}

	void setCleanupBatchSize(int cleanupBatchSize) {
		this.cleanupBatchSize = cleanupBatchSize;
	}
}
