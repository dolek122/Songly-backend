package com.songly.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sam interwał i włącznik zadania czyta {@code @Scheduled} /
 * {@code @ConditionalOnProperty} wprost z propercji — tu jest tylko to, czego
 * potrzebuje kod.
 */
@ConfigurationProperties(prefix = "songly.security.session-cleanup")
class SessionCleanupProperties {

	private Duration retention = Duration.ofDays(7);
	private int batchSize = 5_000;
	private int maxBatchesPerRun = 20;

	/**
	 * Jak długo po śmierci trzymamy sesję, zanim ją skasujemy. Zero byłoby poprawne
	 * funkcjonalnie — martwa sesja nikogo już nie uwierzytelni — ale tydzień
	 * zostawia okno na zdiagnozowanie „dlaczego mnie wylogowało", zanim ślad
	 * zniknie.
	 */
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

	/**
	 * Sufit na jeden przebieg. Zaniedbana tabela ma się drenować przez kolejne
	 * przebiegi, a nie w jednym, który trzyma połączenie i blokuje wiersze przez
	 * kwadrans.
	 */
	int getMaxBatchesPerRun() {
		return maxBatchesPerRun;
	}

	void setMaxBatchesPerRun(int maxBatchesPerRun) {
		this.maxBatchesPerRun = maxBatchesPerRun;
	}
}
