package com.songly.nowplaying;

import com.songly.social.SocialGraph;
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

	NowPlayingPoller(NowPlayingStreamHub hub, SocialGraph socialGraph, NowPlayingService nowPlayingService) {
		this.hub = hub;
		this.socialGraph = socialGraph;
		this.nowPlayingService = nowPlayingService;
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
		for (UUID userId : nowPlayingService.pollableUserIds(observed)) {
			try {
				nowPlayingService.refreshFromProvider(userId);
				refreshed++;
			} catch (RuntimeException ex) {
				// Wygasły refresh token, błąd Spotify, zerwana sieć — jeden użytkownik nie
				// może położyć całego przebiegu, reszta obserwowanych ma się odświeżyć.
				log.warn("Poll statusu nie powiódł się dla {}: {}", userId, ex.toString());
			}
		}
		log.debug("Poll now-playing: odświeżono {} z {} obserwowanych użytkowników", refreshed, observed.size());
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
