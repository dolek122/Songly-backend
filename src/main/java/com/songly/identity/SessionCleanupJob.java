package com.songly.identity;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Usuwa sesje, które nie mogą już nikogo uwierzytelnić.
 *
 * <p>
 * Tabela {@code sessions} rosła dotąd bez żadnego górnego ograniczenia: każde
 * logowanie wstawia wiersz, a każde odświeżenie tokenu wstawia kolejny i
 * revoke'uje poprzedni. Przy access tokenie żyjącym 15 minut aktywny użytkownik
 * zostawia ~4 martwe wiersze na godzinę — i nic ich nigdy nie zbierało. To nie
 * jest problem poprawności (martwy wiersz nikogo nie wpuści), tylko rosnący
 * koszt każdego zapytania do tej tabeli i backupu bazy.
 */
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

	/**
	 * {@code fixedDelay}, nie {@code fixedRate}: gdy przebieg przeciągnie się na
	 * dużym zaległym zbiorze, kolejny ma zacząć się po nim, a nie nałożyć się na
	 * niego i walczyć z nim o te same wiersze.
	 *
	 * <p>
	 * Sam job celowo <b>nie</b> jest transakcyjny — transakcję otwiera dopiero
	 * pojedyncze kasowanie w repozytorium, dzięki czemu każda porcja commituje się
	 * osobno. Gdyby transakcja obejmowała cały przebieg, dzielenie na porcje nie
	 * dawałoby niczego.
	 */
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
			// Sprzątanie jest higieną, nie ścieżką krytyczną — nieudany przebieg ma się
			// odnotować i poczekać na kolejny, a nie zasypać logów stack tracem.
			log.warn("Sprzątanie sesji nie powiodło się (usunięto {} przed błędem): {}", total, ex.toString());
			return;
		}
		if (total > 0) {
			log.info("Usunięto {} martwych sesji starszych niż {}", total, properties.getRetention());
		}
	}
}
