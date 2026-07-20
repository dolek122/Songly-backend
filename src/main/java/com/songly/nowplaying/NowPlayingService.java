package com.songly.nowplaying;

import com.songly.nowplaying.dto.ManualNowPlayingRequest;
import com.songly.nowplaying.dto.NowPlayingStatusResponse;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Status "co teraz gra" cache'owany w Redis (Claude.md §7): Spotify nie ma
 * webhooków, więc bez cache każdy odczyt feedu biłby w ich API i rate limity.
 * Ręcznie ustawiony status (fallback dla serwisów bez integracji, np. Apple
 * Music/Tidal — Claude.md §7) żyje w osobnym kluczu z własnym, dłuższym TTL i
 * ma pierwszeństwo przed providerem, dopóki nie wygaśnie.
 */
@Service
@EnableConfigurationProperties(NowPlayingProperties.class)
class NowPlayingService {

	private static final String STATE_KEY_PREFIX = "now-playing:oauth-state:";
	private static final String CACHE_KEY_PREFIX = "now-playing:status:";
	private static final String MANUAL_KEY_PREFIX = "now-playing:manual:";
	private static final String CACHE_NOTHING_PLAYING = "NOTHING_PLAYING";
	private static final String DEFAULT_MANUAL_SOURCE = "manual";

	private final MusicProvider musicProvider;
	private final MusicProviderLinkRepository linkRepository;
	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;
	private final NowPlayingProperties properties;
	private final SecureRandom secureRandom = new SecureRandom();

	NowPlayingService(MusicProvider musicProvider, MusicProviderLinkRepository linkRepository,
			StringRedisTemplate redisTemplate, ObjectMapper objectMapper, NowPlayingProperties properties) {
		this.musicProvider = musicProvider;
		this.linkRepository = linkRepository;
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	String beginConnect(UUID userId) {
		byte[] bytes = new byte[24];
		secureRandom.nextBytes(bytes);
		String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		redisTemplate.opsForValue().set(STATE_KEY_PREFIX + state, userId.toString(), properties.getOauthStateTtl());
		return musicProvider.buildAuthorizeUrl(state);
	}

	void completeConnect(String state, String authorizationCode) {
		String key = STATE_KEY_PREFIX + state;
		String userId = redisTemplate.opsForValue().getAndDelete(key);
		if (userId == null) {
			throw new InvalidOAuthStateException();
		}
		musicProvider.connect(UUID.fromString(userId), authorizationCode);
	}

	@Transactional
	void disconnect(UUID userId) {
		linkRepository.deleteByUserIdAndProvider(userId, musicProvider.name());
		redisTemplate.delete(CACHE_KEY_PREFIX + userId);
	}

	void setManualStatus(UUID userId, ManualNowPlayingRequest request) {
		String source = request.source() != null && !request.source().isBlank() ? request.source()
				: DEFAULT_MANUAL_SOURCE;
		NowPlayingStatusResponse response = new NowPlayingStatusResponse(source, request.title(), request.artist(),
				request.album(), null, true, Instant.now());
		redisTemplate.opsForValue().set(MANUAL_KEY_PREFIX + userId, objectMapper.writeValueAsString(response),
				properties.getManualStatusTtl());
	}

	void clearManualStatus(UUID userId) {
		redisTemplate.delete(MANUAL_KEY_PREFIX + userId);
	}

	Optional<NowPlayingStatusResponse> getStatus(UUID userId) {
		String manual = redisTemplate.opsForValue().get(MANUAL_KEY_PREFIX + userId);
		if (manual != null) {
			return Optional.of(objectMapper.readValue(manual, NowPlayingStatusResponse.class));
		}
		String cacheKey = CACHE_KEY_PREFIX + userId;
		String cached = redisTemplate.opsForValue().get(cacheKey);
		if (cached != null) {
			return CACHE_NOTHING_PLAYING.equals(cached)
					? Optional.empty()
					: Optional.of(objectMapper.readValue(cached, NowPlayingStatusResponse.class));
		}
		Optional<NowPlayingTrack> track = musicProvider.getCurrentlyPlaying(userId);
		if (track.isEmpty()) {
			redisTemplate.opsForValue().set(cacheKey, CACHE_NOTHING_PLAYING, properties.getCacheTtl());
			return Optional.empty();
		}
		NowPlayingTrack t = track.get();
		NowPlayingStatusResponse response = new NowPlayingStatusResponse(musicProvider.name(), t.title(), t.artist(),
				t.album(), t.imageUrl(), t.playing(), Instant.now());
		redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(response), properties.getCacheTtl());
		return Optional.of(response);
	}
}
