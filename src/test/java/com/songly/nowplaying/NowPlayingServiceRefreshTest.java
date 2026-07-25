package com.songly.nowplaying;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.songly.nowplaying.dto.NowPlayingStatusResponse;
import com.songly.profile.ProfileDirectory;
import com.songly.social.SocialGraph;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

/**
 * Serwerowy poll (ADR 0007, Etap 1b): odświeżanie z providera z pominięciem
 * cache'a i publikacja zdarzenia wyłącznie przy realnej zmianie utworu.
 */
@ExtendWith(MockitoExtension.class)
class NowPlayingServiceRefreshTest {

	private static final String CACHE_KEY_PREFIX = "now-playing:status:";
	private static final String MANUAL_KEY_PREFIX = "now-playing:manual:";

	@Mock
	private MusicProvider musicProvider;

	@Mock
	private MusicProviderLinkRepository linkRepository;

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	@Mock
	private SocialGraph socialGraph;

	@Mock
	private ProfileDirectory profileDirectory;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final NowPlayingProperties properties = new NowPlayingProperties();
	private final UUID userId = UUID.randomUUID();

	private NowPlayingService service;

	@BeforeEach
	void setUp() {
		service = new NowPlayingService(musicProvider, linkRepository, redisTemplate, objectMapper, properties,
				socialGraph, profileDirectory, eventPublisher);
	}

	private void provider(String title) {
		when(musicProvider.name()).thenReturn("spotify");
		when(musicProvider.getCurrentlyPlaying(userId))
				.thenReturn(Optional.of(new NowPlayingTrack(title, "Queen", "A Night at the Opera", null, true)));
	}

	private String cachedJson(String title) {
		return objectMapper.writeValueAsString(new NowPlayingStatusResponse("spotify", title, "Queen",
				"A Night at the Opera", null, true, Instant.now().minusSeconds(30)));
	}

	@Test
	void publishesEvent_andRefreshesCache_whenTrackChanged() {
		when(redisTemplate.hasKey(MANUAL_KEY_PREFIX + userId)).thenReturn(false);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(CACHE_KEY_PREFIX + userId)).thenReturn(cachedJson("Bohemian Rhapsody"));
		provider("Love of My Life");

		service.refreshFromProvider(userId);

		verify(valueOperations).set(eq(CACHE_KEY_PREFIX + userId), anyString(), eq(Duration.ofSeconds(45)));
		ArgumentCaptor<NowPlayingStatusChangedEvent> event = ArgumentCaptor
				.forClass(NowPlayingStatusChangedEvent.class);
		verify(eventPublisher).publishEvent(event.capture());
		assertThat(event.getValue().userId()).isEqualTo(userId);
		assertThat(event.getValue().status().title()).isEqualTo("Love of My Life");
	}

	@Test
	void publishesEvent_whenNothingWasCachedBefore() {
		when(redisTemplate.hasKey(MANUAL_KEY_PREFIX + userId)).thenReturn(false);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(CACHE_KEY_PREFIX + userId)).thenReturn(null);
		provider("Bohemian Rhapsody");

		service.refreshFromProvider(userId);

		verify(eventPublisher).publishEvent(any(NowPlayingStatusChangedEvent.class));
	}

	@Test
	void refreshesCacheWithoutEvent_whenSameTrackStillPlaying() {
		when(redisTemplate.hasKey(MANUAL_KEY_PREFIX + userId)).thenReturn(false);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(CACHE_KEY_PREFIX + userId)).thenReturn(cachedJson("Bohemian Rhapsody"));
		provider("Bohemian Rhapsody");

		service.refreshFromProvider(userId);

		// TTL trzeba odnowić mimo braku zmiany, inaczej status zniknie z /friends
		verify(valueOperations).set(eq(CACHE_KEY_PREFIX + userId), anyString(), eq(Duration.ofSeconds(45)));
		verify(eventPublisher, never()).publishEvent(any(NowPlayingStatusChangedEvent.class));
	}

	@Test
	void cachesNothingPlaying_withoutEvent_whenProviderReturnsNothing() {
		when(redisTemplate.hasKey(MANUAL_KEY_PREFIX + userId)).thenReturn(false);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(CACHE_KEY_PREFIX + userId)).thenReturn(cachedJson("Bohemian Rhapsody"));
		when(musicProvider.getCurrentlyPlaying(userId)).thenReturn(Optional.empty());

		service.refreshFromProvider(userId);

		verify(valueOperations).set(CACHE_KEY_PREFIX + userId, "NOTHING_PLAYING", Duration.ofSeconds(45));
		verify(eventPublisher, never()).publishEvent(any(NowPlayingStatusChangedEvent.class));
	}

	@Test
	void skipsProviderEntirely_whenManualStatusIsSet() {
		when(redisTemplate.hasKey(MANUAL_KEY_PREFIX + userId)).thenReturn(true);

		service.refreshFromProvider(userId);

		verifyNoInteractions(musicProvider, eventPublisher);
	}

	@Test
	void pollableUserIds_asksRepositoryOnceForWholeBatch() {
		Set<UUID> candidates = Set.of(userId, UUID.randomUUID());
		when(musicProvider.name()).thenReturn("spotify");
		when(linkRepository.findUserIdsByProviderAndUserIdIn("spotify", candidates)).thenReturn(Set.of(userId));

		assertThat(service.pollableUserIds(candidates)).containsExactly(userId);
	}

	@Test
	void pollableUserIds_shortCircuitsOnEmptyInput() {
		assertThat(service.pollableUserIds(Set.of())).isEmpty();
		verifyNoInteractions(linkRepository);
	}
}
