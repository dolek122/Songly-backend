package com.songly.identity;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "songly.security.registration.cleanup-enabled", havingValue = "true", matchIfMissing = true)
class PendingRegistrationCleanupJob {

	private static final Logger log = LoggerFactory.getLogger(PendingRegistrationCleanupJob.class);

	private final PendingRegistrationRepository repository;

	PendingRegistrationCleanupJob(PendingRegistrationRepository repository) {
		this.repository = repository;
	}

	@Scheduled(fixedDelayString = "${songly.security.registration.cleanup-interval:PT1H}")
	void removeExpiredRegistrations() {
		try {
			int deleted = repository.deleteExpired(Instant.now());
			if (deleted > 0) {
				log.info("Usunięto {} wygasłych zgłoszeń rejestracji", deleted);
			}
		} catch (RuntimeException ex) {
			log.warn("Sprzątanie wygasłych rejestracji nie powiodło się: {}", ex.toString());
		}
	}
}
