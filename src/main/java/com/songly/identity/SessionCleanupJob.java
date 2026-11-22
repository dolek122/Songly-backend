package com.songly.identity;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(SessionCleanupProperties.class)
@ConditionalOnProperty(name = "songly.security.session-cleanup.enabled", havingValue = "true", matchIfMissing = true)
class SessionCleanupJob {

	private static final Logger log = LoggerFactory.getLogger(SessionCleanupJob.class);

	private final SessionRepository sessionRepository;
	private final SessionCleanupProperties properties;

	SessionCleanupJob(SessionRepository sessionRepository, SessionCleanupProperties properties) {
		this.sessionRepository = sessionRepository;
		this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${songly.security.session-cleanup.interval:PT1H}")
	void removeDeadSessions() {
		Instant cutoff = Instant.now().minus(properties.getRetention());
		int total = 0;
		try {
			for (int batch = 0; batch < properties.getMaxBatchesPerRun(); batch++) {
				int deleted = sessionRepository.deleteDeadSessions(cutoff, properties.getBatchSize());
				total += deleted;
				if (deleted < properties.getBatchSize()) {
					break;
				}
			}
		} catch (RuntimeException ex) {
			log.warn("Sprzątanie sesji nie powiodło się (usunięto {} przed błędem): {}", total, ex.toString());
			return;
		}
		if (total > 0) {
			log.info("Usunięto {} martwych sesji starszych niż {}", total, properties.getRetention());
		}
	}
}
