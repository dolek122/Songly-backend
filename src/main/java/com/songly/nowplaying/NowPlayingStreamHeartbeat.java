package com.songly.nowplaying;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Utrzymanie strumieni SSE przy życiu (ADR 0007, Etap 4). Bez ruchu na
 * połączeniu proxy i load balancery zamykają je po kilkudziesięciu sekundach
 * bezczynności, a presence bywa cicha godzinami — komentarz co kilkanaście
 * sekund kosztuje kilka bajtów i oszczędza pętlę rozłączeń i reconnectów.
 *
 * <p>
 * Przy okazji jest jedynym sposobem, żeby zauważyć gniazda zerwane po cichu:
 * błąd zapisu wypada dopiero przy próbie wysłania, a bez heartbeatu taki
 * strumień siedziałby w rejestrze do timeoutu.
 */
@Component
@ConditionalOnProperty(name = "songly.now-playing.stream.heartbeat.enabled", havingValue = "true", matchIfMissing = true)
class NowPlayingStreamHeartbeat {

	private final NowPlayingStreamHub hub;

	NowPlayingStreamHeartbeat(NowPlayingStreamHub hub) {
		this.hub = hub;
	}

	@Scheduled(fixedDelayString = "${songly.now-playing.stream.heartbeat.interval:PT20S}")
	void sendHeartbeat() {
		hub.heartbeat();
	}
}
