package com.songly.nowplaying;

import com.songly.nowplaying.dto.FriendNowPlayingResponse;
import com.songly.nowplaying.dto.ManualNowPlayingRequest;
import com.songly.nowplaying.dto.NowPlayingStatusResponse;
import com.songly.profile.ProfileDirectory;
import com.songly.social.SocialGraph;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
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
	private final SocialGraph socialGraph;
	private final ProfileDirectory profileDirectory;
	private final ApplicationEventPublisher eventPublisher;
	private final SecureRandom secureRandom = new SecureRandom();

	NowPlayingService(MusicProvider musicProvider, MusicProviderLinkRepository linkRepository,
			StringRedisTemplate redisTemplate, ObjectMapper objectMapper, NowPlayingProperties properties,
			SocialGraph socialGraph, ProfileDirectory profileDirectory, ApplicationEventPublisher eventPublisher) {
		this.musicProvider = musicProvider;
		this.linkRepository = linkRepository;
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
		this.properties = properties;
		this.socialGraph = socialGraph;
		this.profileDirectory = profileDirectory;
		this.eventPublisher = eventPublisher;
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

	@Transactional
	void deleteAccountData(UUID userId) {
		linkRepository.deleteByUserId(userId);
		redisTemplate.delete(CACHE_KEY_PREFIX + userId);
		redisTemplate.delete(MANUAL_KEY_PREFIX + userId);
	}

	void setManualStatus(UUID userId, ManualNowPlayingRequest request) {
		String source = request.source() != null && !request.source().isBlank()
				? request.source()
				: DEFAULT_MANUAL_SOURCE;
		NowPlayingStatusResponse response = new NowPlayingStatusResponse(source, request.title(), request.artist(),
				request.album(), null, true, Instant.now());
		redisTemplate.opsForValue().set(MANUAL_KEY_PREFIX + userId, objectMapper.writeValueAsString(response),
				properties.getManualStatusTtl());
		eventPublisher.publishEvent(new NowPlayingStatusChangedEvent(userId, response));
	}

	void clearManualStatus(UUID userId) {
		redisTemplate.delete(MANUAL_KEY_PREFIX + userId);
	}

	/**
	 * Statusy "co teraz gra" znajomych — wyłącznie z cache'a (manual > provider),
	 * bez odpytywania Spotify per znajomy: jeden request nie może wywołać N zapytań
	 * do providera i pobić rate limitów (Claude.md §7). Cache zapełnia się, gdy
	 * znajomi odczytują własny status, oraz z serwerowego polla obserwowanych
	 * użytkowników ({@link NowPlayingPoller}). Pomija znajomych bez aktualnego
	 * statusu. Granice modułów przez publiczne interfejsy {@link SocialGraph} i
	 * {@link ProfileDirectory} — bez dostępu do encji innych modułów (Claude.md
	 * §2).
	 */
	@Transactional(readOnly = true)
	List<FriendNowPlayingResponse> friendsNowPlaying(UUID userId) {
		Set<UUID> friendIds = socialGraph.friendIds(userId);
		if (friendIds.isEmpty()) {
			return List.of();
		}
		Map<UUID, String> names = profileDirectory.displayNames(friendIds);
		Map<UUID, String> avatars = profileDirectory.avatarUrls(friendIds);
		return friendIds.stream().flatMap(friendId -> getCachedStatus(friendId).stream().map(
				status -> new FriendNowPlayingResponse(friendId, names.get(friendId), avatars.get(friendId), status)))
				.sorted(Comparator.comparing(FriendNowPlayingResponse::displayName,
						Comparator.nullsLast(Comparator.naturalOrder())))
				.toList();
	}

	/**
	 * Status tylko z cache'a Redis (manual > provider), bez fallbacku do providera.
	 */
	Optional<NowPlayingStatusResponse> getCachedStatus(UUID userId) {
		String manual = redisTemplate.opsForValue().get(MANUAL_KEY_PREFIX + userId);
		if (manual != null) {
			return Optional.of(objectMapper.readValue(manual, NowPlayingStatusResponse.class));
		}
		String cached = redisTemplate.opsForValue().get(CACHE_KEY_PREFIX + userId);
		if (cached != null && !CACHE_NOTHING_PLAYING.equals(cached)) {
			return Optional.of(objectMapper.readValue(cached, NowPlayingStatusResponse.class));
		}
		return Optional.empty();
	}

	Optional<NowPlayingStatusResponse> getStatus(UUID userId) {
		String manual = redisTemplate.opsForValue().get(MANUAL_KEY_PREFIX + userId);
		if (manual != null) {
			return Optional.of(objectMapper.readValue(manual, NowPlayingStatusResponse.class));
		}
		String cached = redisTemplate.opsForValue().get(CACHE_KEY_PREFIX + userId);
		if (cached != null) {
			return CACHE_NOTHING_PLAYING.equals(cached)
					? Optional.empty()
					: Optional.of(objectMapper.readValue(cached, NowPlayingStatusResponse.class));
		}
		return fetchAndCache(userId);
	}

	/**
	 * Z podanych użytkowników ci, którzy mają podłączonego providera — jedno
	 * zapytanie zamiast N sprawdzeń. Serwerowy poll (ADR 0007, Etap 1b) leci co
	 * kilkadziesiąt sekund po wszystkich obserwowanych, więc nie może płacić
	 * zapytaniem (ani wyjątkiem {@link ProviderNotConnectedException}) za każdego
	 * użytkownika bez integracji — a tych jest większość.
	 */
	@Transactional(readOnly = true)
	Set<UUID> pollableUserIds(Set<UUID> candidates) {
		return candidates.isEmpty()
				? Set.of()
				: linkRepository.findUserIdsByProviderAndUserIdIn(musicProvider.name(), candidates);
	}

	/**
	 * Odpytuje providera i odświeża cache <b>pomijając</b> cache'owaną wartość —
	 * inaczej niż {@link #getStatus}, bo to poll ma cache zapełniać, a nie z niego
	 * czytać (ADR 0007, Etap 1b). Zdarzenie leci tylko przy realnej zmianie utworu:
	 * bez tego każdy przebieg polla pushowałby przez SSE ten sam status.
	 *
	 * <p>
	 * Ręcznie ustawiony status ma pierwszeństwo przed providerem, więc dla takich
	 * użytkowników poll jest pomijany — odpytanie Spotify i tak nie zmieniłoby
	 * tego, co widzą znajomi.
	 */
	void refreshFromProvider(UUID userId) {
		if (Boolean.TRUE.equals(redisTemplate.hasKey(MANUAL_KEY_PREFIX + userId))) {
			return;
		}
		String previous = redisTemplate.opsForValue().get(CACHE_KEY_PREFIX + userId);
		fetchAndCache(userId).filter(fresh -> isDifferentTrack(previous, fresh))
				.ifPresent(fresh -> eventPublisher.publishEvent(new NowPlayingStatusChangedEvent(userId, fresh)));
	}

	private Optional<NowPlayingStatusResponse> fetchAndCache(UUID userId) {
		String cacheKey = CACHE_KEY_PREFIX + userId;
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

	/**
	 * Porównanie po samym utworze — {@code fetchedAt} zmienia się przy każdym
	 * odczycie, więc równość całych rekordów uznałaby każdy poll za zmianę.
	 */
	private boolean isDifferentTrack(String previousJson, NowPlayingStatusResponse fresh) {
		if (previousJson == null || CACHE_NOTHING_PLAYING.equals(previousJson)) {
			return true;
		}
		NowPlayingStatusResponse previous = objectMapper.readValue(previousJson, NowPlayingStatusResponse.class);
		return !Objects.equals(previous.title(), fresh.title()) || !Objects.equals(previous.artist(), fresh.artist())
				|| !Objects.equals(previous.album(), fresh.album()) || previous.playing() != fresh.playing();
	}
}
