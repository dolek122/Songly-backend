package com.songly.nowplaying;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
