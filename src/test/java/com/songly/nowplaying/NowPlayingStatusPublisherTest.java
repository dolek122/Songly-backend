package com.songly.nowplaying;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.songly.nowplaying.dto.NowPlayingStatusResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

/**
 * Znacznik „ogłosiliśmy granie" utrzymywany przy publikacji (ADR 0007) — z
 * niego poll rozpoznaje, że status wygasł, a nie że użytkownik nigdy nic nie
 * grał.
 */
@ExtendWith(MockitoExtension.class)
class NowPlayingStatusPublisherTest {

	private static final String ANNOUNCED_KEY_PREFIX = "now-playing:announced:";

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final NowPlayingProperties properties = new NowPlayingProperties();
	private final UUID userId = UUID.randomUUID();

	private NowPlayingStatusPublisher publisher() {
		return new NowPlayingStatusPublisher(redisTemplate, objectMapper, properties);
	}

	@Test
	void publishingStatus_marksUserAsAnnounced() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);

		publisher().publish(userId,
				new NowPlayingStatusResponse("spotify", "Bohemian Rhapsody", "Queen", null, null, true, Instant.now()));

		verify(redisTemplate).convertAndSend(eq("now-playing:status-changed"), anyString());
		verify(valueOperations).set(ANNOUNCED_KEY_PREFIX + userId, "1", Duration.ofHours(6));
	}

	/**
	 * Znacznik musi zniknąć razem ze statusem, inaczej poll zapętliłby „stopped".
	 */
	@Test
	void publishingStopped_clearsTheMarker() {
		publisher().publishStopped(userId);

		verify(redisTemplate).convertAndSend(eq("now-playing:status-changed"), anyString());
		verify(redisTemplate).delete(ANNOUNCED_KEY_PREFIX + userId);
	}
}
