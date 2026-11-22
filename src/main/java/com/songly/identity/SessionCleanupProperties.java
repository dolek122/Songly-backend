package com.songly.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.security.session-cleanup")
class SessionCleanupProperties {

	private Duration retention = Duration.ofDays(7);
	private int batchSize = 5_000;
	private int maxBatchesPerRun = 20;

	Duration getRetention() {
		return retention;
	}

	void setRetention(Duration retention) {
		this.retention = retention;
	}

	int getBatchSize() {
		return batchSize;
	}

	void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}

	int getMaxBatchesPerRun() {
		return maxBatchesPerRun;
	}

	void setMaxBatchesPerRun(int maxBatchesPerRun) {
		this.maxBatchesPerRun = maxBatchesPerRun;
	}
}
