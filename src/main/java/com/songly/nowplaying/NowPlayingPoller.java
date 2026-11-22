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

	@Scheduled(fixedDelayString = "${songly.now-playing.poll.interval:PT30S}")
	void pollObservedUsers() {
		Set<UUID> observed = observedUserIds();
		if (observed.isEmpty()) {
			return;
		}
		int refreshed = 0;
		for (UUID userId : pollableUserIds(observed)) {
			try {
				if (!nowPlayingService.tryAcquirePollLock(userId)) {
					continue;
				}
				nowPlayingService.refreshFromProvider(userId);
				refreshed++;
			} catch (SpotifyApiException ex) {
				if (ex.isRateLimited()) {
					applyBackoff(ex);
					break;
				}
				log.warn("Poll statusu nie powiódł się dla {}: {}", userId, ex.toString());
			} catch (RuntimeException ex) {
				log.warn("Poll statusu nie powiódł się dla {}: {}", userId, ex.toString());
			}
		}
		for (UUID userId : observed) {
			try {
				nowPlayingService.publishStoppedIfStatusExpired(userId);
			} catch (RuntimeException ex) {
				log.warn("Nie udało się sprawdzić wygaśnięcia statusu dla {}: {}", userId, ex.toString());
			}
		}
		log.debug("Poll now-playing: odświeżono {} z {} obserwowanych użytkowników", refreshed, observed.size());
	}

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

	private void applyBackoff(SpotifyApiException ex) {
		Duration backoff = ex.retryAfter() != null ? ex.retryAfter() : properties.getPoll().getProviderBackoff();
		log.warn("Provider odrzucił zapytanie limitem — wstrzymuję poll na {}", backoff);
		try {
			nowPlayingService.applyProviderBackoff(backoff);
		} catch (RuntimeException redisFailure) {
			log.warn("Nie udało się zapisać backoffu providera: {}", redisFailure.toString());
		}
	}

	private Set<UUID> observedUserIds() {
		return hub.connectedUserIds().stream().flatMap(viewerId -> socialGraph.friendIds(viewerId).stream())
				.collect(Collectors.toSet());
	}
}
