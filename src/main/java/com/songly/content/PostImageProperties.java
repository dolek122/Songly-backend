package com.songly.content;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.content.post-image")
class PostImageProperties {

	private int maxPending = 10;

	private Duration pendingTtl = Duration.ofHours(24);

	private int cleanupBatchSize = 500;

	int getMaxPending() {
		return maxPending;
	}

	void setMaxPending(int maxPending) {
		this.maxPending = maxPending;
	}

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
