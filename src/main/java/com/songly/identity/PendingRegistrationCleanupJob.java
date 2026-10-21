package com.songly.identity;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Usuwa odłożone rejestracje po terminie (ADR 0009).
 *
 * <p>
 * Wygasły link nikogo już nie wpuści, ale wiersz trzymał dalej adres e-mail i
 * hash hasła osoby, która konta nigdy nie założyła — czasem kogoś, czyj adres
 * wpisał ktoś obcy. Mail weryfikacyjny obiecuje temu człowiekowi wprost, że
 * zgłoszenie „samo wygaśnie" i nie zapiszemy o nim nic więcej. Bez tego zadania
 * było to prawdą tylko o linku, a nie o danych: wiersz znikał wyłącznie wtedy,
 * gdy ktoś zarejestrował się ponownie na ten sam adres.
 *
 * <p>
 * Bez okna retencji, w przeciwieństwie do sesji: tam tydzień służy diagnozie
 * „dlaczego mnie wylogowało", a tu nie ma czego diagnozować — po terminie
 * zgłoszenie jest martwe z definicji.
 */
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
			// Higiena, nie ścieżka krytyczna — tak samo jak sprzątanie sesji.
			log.warn("Sprzątanie wygasłych rejestracji nie powiodło się: {}", ex.toString());
		}
	}
}
