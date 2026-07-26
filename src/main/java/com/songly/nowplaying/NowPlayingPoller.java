package com.songly.nowplaying;

import com.songly.social.SocialGraph;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Serwerowy poll statusu u providera (ADR 0007, Etap 1b). Spotify nie ma
 * webhooków, więc ktoś musi pytać — robi to jeden harmonogram na instancję
 * zamiast N klientów, dzięki czemu ruch do Spotify i rate limity są pod
 * kontrolą w jednym miejscu (Claude.md §7).
 *
 * <p>
 * Odpytujemy <b>wyłącznie użytkowników, których ktoś aktualnie obserwuje</b> —
 * czyli znajomych osób z otwartym strumieniem SSE. Nikt nie patrzy, nikogo nie
 * pytamy: bez tego filtra poll rósłby z liczbą kont, a nie z liczbą realnych
 * widzów.
 */
@Component
@ConditionalOnProperty(name = "songly.now-playing.poll.enabled", havingValue = "true", matchIfMissing = true)
class NowPlayingPoller {

	private static final Logger log = LoggerFactory.getLogger(NowPlayingPoller.class);

	private final NowPlayingStreamHub hub;
	private final SocialGraph socialGraph;
	private final NowPlayingService nowPlayingService;
	private final NowPlayingProperties properties;

	NowPlayingPoller(NowPlayingStreamHub hub, SocialGraph socialGraph, NowPlayingService nowPlayingService,
			NowPlayingProperties properties) {
		this.hub = hub;
		this.socialGraph = socialGraph;
		this.nowPlayingService = nowPlayingService;
		this.properties = properties;
	}

	/**
	 * {@code fixedDelay}, nie {@code fixedRate}: gdy przebieg przeciągnie się przez
	 * wolne odpowiedzi Spotify, kolejny ma zacząć się po nim, a nie nałożyć się na
	 * niego.
	 */
	@Scheduled(fixedDelayString = "${songly.now-playing.poll.interval:PT30S}")
	void pollObservedUsers() {
		Set<UUID> observed = observedUserIds();
		if (observed.isEmpty()) {
			return;
		}
		int refreshed = 0;
		for (UUID userId : pollableUserIds(observed)) {
			try {
				// Przy wielu instancjach ten sam obserwowany user siedzi na liście u każdej
				// z nich — lock sprawia, że w danym cyklu odpytuje go dokładnie jedna
				// (ADR 0007, Etap 4). Zmianę i tak roznosi kanał presence do pozostałych.
				// Wewnątrz try, bo nieodebrany lock i błąd Redisa to dwie różne rzeczy:
				// pierwsze jest normalne, drugie nie może zabrać reszty przebiegu.
				if (!nowPlayingService.tryAcquirePollLock(userId)) {
					continue;
				}
				nowPlayingService.refreshFromProvider(userId);
				refreshed++;
			} catch (SpotifyApiException ex) {
				if (ex.isRateLimited()) {
					// Rate limit jest wspólny dla całego klienta, więc kolejni użytkownicy w
					// tym przebiegu dostaliby dokładnie to samo 429 — i tylko pogłębili karę.
					applyBackoff(ex);
					break;
				}
				log.warn("Poll statusu nie powiódł się dla {}: {}", userId, ex.toString());
			} catch (RuntimeException ex) {
				// Odwołana zgoda, zerwana sieć, błąd Redisa — jeden użytkownik nie może
				// położyć całego przebiegu, reszta obserwowanych ma się odświeżyć.
				log.warn("Poll statusu nie powiódł się dla {}: {}", userId, ex.toString());
			}
		}
		// Osobno po *wszystkich* obserwowanych, nie tylko po tych z providerem: status
		// ręczny wygasa sam z siebie, a jego właściciel z definicji nie ma czego
		// odpytywać, więc tylko tędy da się zauważyć, że przestał grać.
		for (UUID userId : observed) {
			try {
				nowPlayingService.publishStoppedIfStatusExpired(userId);
			} catch (RuntimeException ex) {
				log.warn("Nie udało się sprawdzić wygaśnięcia statusu dla {}: {}", userId, ex.toString());
			}
		}
		log.debug("Poll now-playing: odświeżono {} z {} obserwowanych użytkowników", refreshed, observed.size());
	}

	/**
	 * Zapytanie o użytkowników z podłączonym providerem nie może przewrócić całego
	 * przebiegu: sprawdzenie wygasłych statusów niżej nie zależy od providera i ma
	 * się wykonać nawet wtedy, gdy baza akurat nie odpowiada.
	 *
	 * <p>
	 * Pusty zbiór w trakcie backoffu wycisza tylko ruch do providera — statusy
	 * ręczne i tak wygasają same, więc zamiatanie niżej musi biec dalej.
	 */
	private Set<UUID> pollableUserIds(Set<UUID> observed) {
		try {
			if (nowPlayingService.isProviderBackoffActive()) {
				log.debug("Pomijam odpytanie providera — trwa backoff po jego rate limicie");
				return Set.of();
			}
			return nowPlayingService.pollableUserIds(observed);
		} catch (RuntimeException ex) {
			log.warn("Nie udało się ustalić, kogo odpytać u providera: {}", ex.toString());
			return Set.of();
		}
	}

	/**
	 * Wolimy {@code Retry-After} od providera niż własną zgadywankę — on jeden wie,
	 * kiedy okno limitu się odnowi.
	 */
	private void applyBackoff(SpotifyApiException ex) {
		Duration backoff = ex.retryAfter() != null ? ex.retryAfter() : properties.getPoll().getProviderBackoff();
		log.warn("Provider odrzucił zapytanie limitem — wstrzymuję poll na {}", backoff);
		try {
			nowPlayingService.applyProviderBackoff(backoff);
		} catch (RuntimeException redisFailure) {
			// Redis nie odpowiada: przebieg i tak przerywamy, tylko kolejny znów spróbuje.
			log.warn("Nie udało się zapisać backoffu providera: {}", redisFailure.toString());
		}
	}

	/**
	 * Obserwowani = znajomi użytkowników z otwartym strumieniem. Jedno zapytanie o
	 * graf na widza — widzów jest z definicji tylu, ilu trzyma otwarte połączenie z
	 * tą instancją.
	 */
	private Set<UUID> observedUserIds() {
		return hub.connectedUserIds().stream().flatMap(viewerId -> socialGraph.friendIds(viewerId).stream())
				.collect(Collectors.toSet());
	}
}
