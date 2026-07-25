package com.songly.nowplaying;

import com.songly.nowplaying.dto.NowPlayingStatusResponse;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Publikacja zmian statusu na kanał Redis (ADR 0007, Etap 2). Zastąpiła szynę
 * wewnątrzprocesową (Spring events) z Etapu 1a: producent nie wie, na której
 * instancji siedzą subskrybenci SSE, więc zdarzenie musi opuścić proces.
 *
 * <p>
 * Publikujący nie omija kanału dla samego siebie — własna instancja dostaje tę
 * samą wiadomość co pozostałe, więc jest dokładnie jedna ścieżka dostarczania
 * do przetestowania i utrzymania.
 */
@Component
class NowPlayingStatusPublisher {

	static final String CHANNEL = "now-playing:status-changed";

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	NowPlayingStatusPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	void publish(UUID userId, NowPlayingStatusResponse status) {
		NowPlayingStatusChangedMessage message = new NowPlayingStatusChangedMessage(userId, status);
		redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(message));
	}

	/**
	 * "Przestał słuchać" (ADR 0007, Etap 4) — pusty status zamiast utworu. Bez tego
	 * klient musiałby zgadywać koniec odtwarzania z wieku {@code fetchedAt} i
	 * pokazywałby znajomego jako grającego jeszcze długo po tym, jak wyłączył
	 * muzykę.
	 */
	void publishStopped(UUID userId) {
		publish(userId, null);
	}
}
