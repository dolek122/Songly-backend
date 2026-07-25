package com.songly.nowplaying;

import com.songly.nowplaying.dto.FriendNowPlayingResponse;
import com.songly.nowplaying.dto.ManualNowPlayingRequest;
import com.songly.nowplaying.dto.NowPlayingSettingsResponse;
import com.songly.nowplaying.dto.NowPlayingStatusResponse;
import com.songly.nowplaying.dto.UpdateNowPlayingSettingsRequest;
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
import java.util.stream.Collectors;
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
	private static final String POLL_LOCK_KEY_PREFIX = "now-playing:poll-lock:";
	private static final String CACHE_NOTHING_PLAYING = "NOTHING_PLAYING";
	private static final String DEFAULT_MANUAL_SOURCE = "manual";

	private final MusicProvider musicProvider;
	private final MusicProviderLinkRepository linkRepository;
	private final NowPlayingPreferenceRepository preferenceRepository;
	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;
	private final NowPlayingProperties properties;
	private final SocialGraph socialGraph;
	private final ProfileDirectory profileDirectory;
	private final NowPlayingStatusPublisher statusPublisher;
	private final SecureRandom secureRandom = new SecureRandom();

	NowPlayingService(MusicProvider musicProvider, MusicProviderLinkRepository linkRepository,
			NowPlayingPreferenceRepository preferenceRepository, StringRedisTemplate redisTemplate,
			ObjectMapper objectMapper, NowPlayingProperties properties, SocialGraph socialGraph,
			ProfileDirectory profileDirectory, NowPlayingStatusPublisher statusPublisher) {
		this.musicProvider = musicProvider;
		this.linkRepository = linkRepository;
		this.preferenceRepository = preferenceRepository;
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
		this.properties = properties;
		this.socialGraph = socialGraph;
		this.profileDirectory = profileDirectory;
		this.statusPublisher = statusPublisher;
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
		// Ręczny status ma pierwszeństwo i zostaje po odłączeniu providera, więc
		// wtedy użytkownik nadal coś "gra" — "przestał słuchać" byłoby kłamstwem.
		if (!Boolean.TRUE.equals(redisTemplate.hasKey(MANUAL_KEY_PREFIX + userId))) {
			statusPublisher.publishStopped(userId);
		}
	}

	@Transactional
	void deleteAccountData(UUID userId) {
		linkRepository.deleteByUserId(userId);
		preferenceRepository.deleteByUserId(userId);
		redisTemplate.delete(CACHE_KEY_PREFIX + userId);
		redisTemplate.delete(MANUAL_KEY_PREFIX + userId);
		// Bez tego znajomi z otwartym strumieniem widzieliby status konta, którego
		// już nie ma — aż do własnego reconnectu.
		statusPublisher.publishStopped(userId);
	}

	@Transactional(readOnly = true)
	NowPlayingSettingsResponse getSettings(UUID userId) {
		return new NowPlayingSettingsResponse(!preferenceRepository.existsByUserIdAndShareWithFriendsFalse(userId));
	}

	/**
	 * Statusu z cache'a nie kasujemy — jest filtrowany na każdej ścieżce wyjścia
	 * (Claude.md §9), a własny status w {@code /me} ma zostać widoczny dla
	 * właściciela.
	 *
	 * <p>
	 * Samo przestawienie flagi nie wystarcza: znajomy z <b>otwartym</b> strumieniem
	 * nie dostaje już żadnego pushu, więc widziałby ostatni status w nieskończoność
	 * (aż do własnego reconnectu). Dlatego wyłączenie wypycha „przestał słuchać", a
	 * włączenie — aktualny status, żeby wrócił na listę od razu, nie przy
	 * najbliższej zmianie utworu.
	 */
	@Transactional
	void updateSettings(UUID userId, UpdateNowPlayingSettingsRequest request) {
		Instant now = Instant.now();
		preferenceRepository.findById(userId).ifPresentOrElse(
				preference -> preference.updateShareWithFriends(request.shareWithFriends(), now),
				() -> preferenceRepository.save(new NowPlayingPreference(userId, request.shareWithFriends(), now)));
		if (request.shareWithFriends()) {
			getCachedStatus(userId).ifPresent(status -> statusPublisher.publish(userId, status));
		} else {
			statusPublisher.publishStopped(userId);
		}
	}

	/**
	 * Czy użytkownik zgadza się pokazywać status znajomym (ADR 0007, Etap 4).
	 * Sprawdzane przy <b>każdym</b> pushu, nie tylko przy otwarciu strumienia —
	 * ustawienie może się zmienić w trakcie trwania połączenia.
	 */
	@Transactional(readOnly = true)
	boolean sharesStatus(UUID userId) {
		return !preferenceRepository.existsByUserIdAndShareWithFriendsFalse(userId);
	}

	/** Z podanych użytkowników ci, którzy nie wyłączyli widoczności statusu. */
	@Transactional(readOnly = true)
	Set<UUID> sharingUserIds(Set<UUID> candidates) {
		if (candidates.isEmpty()) {
			return Set.of();
		}
		Set<UUID> optedOut = preferenceRepository.findOptedOutUserIds(candidates);
		return candidates.stream().filter(userId -> !optedOut.contains(userId)).collect(Collectors.toSet());
	}

	/**
	 * Wysyła „przestał słuchać", gdy status użytkownika zniknął sam z siebie —
	 * przez wygaśnięcie TTL, nie przez żadną jego akcję (ADR 0007).
	 *
	 * <p>
	 * Bez tego ręcznie ustawiony status (TTL liczony w godzinach) wisiałby na
	 * ekranie znajomego w nieskończoność: użytkownik bez podłączonego providera nie
	 * jest odpytywany, więc nic nie zauważa jego zniknięcia. Redis nie mówi nam o
	 * wygaśnięciu klucza — zamiast włączać keyspace notifications (konfiguracja
	 * serwera, kolejny kanał do utrzymania) sprawdzamy to w przebiegu polla, który
	 * i tak przechodzi po obserwowanych użytkownikach.
	 *
	 * <p>
	 * Znacznik z {@link NowPlayingStatusPublisher} odróżnia „status wygasł" od
	 * „nigdy nic nie grał" — bez niego wysyłalibyśmy „przestał słuchać" w kółko dla
	 * wszystkich milczących użytkowników. Przy wielu instancjach może się zdarzyć
	 * podwójna publikacja (obie sprawdzą znacznik zanim któraś go skasuje); to
	 * nieszkodliwe, bo klient po prostu drugi raz zdejmuje wpis, którego już nie
	 * ma.
	 */
	void publishStoppedIfStatusExpired(UUID userId) {
		if (!Boolean.TRUE.equals(redisTemplate.hasKey(NowPlayingStatusPublisher.ANNOUNCED_KEY_PREFIX + userId))) {
			return;
		}
		if (getCachedStatus(userId).isPresent()) {
			return;
		}
		statusPublisher.publishStopped(userId);
	}

	/**
	 * Krótki lock w Redis, żeby przy wielu instancjach tego samego obserwowanego
	 * użytkownika odpytywał w danym cyklu tylko jeden węzeł (ADR 0007, Etap 4) —
	 * inaczej ruch do Spotify rósłby liniowo z liczbą instancji. Nieodebrany lock
	 * nie boli: zmianę i tak rozniesie kanał presence do wszystkich instancji.
	 */
	boolean tryAcquirePollLock(UUID userId) {
		return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(POLL_LOCK_KEY_PREFIX + userId, "1",
				properties.getPoll().getLockTtl()));
	}

	void setManualStatus(UUID userId, ManualNowPlayingRequest request) {
		String source = request.source() != null && !request.source().isBlank()
				? request.source()
				: DEFAULT_MANUAL_SOURCE;
		NowPlayingStatusResponse response = new NowPlayingStatusResponse(source, request.title(), request.artist(),
				request.album(), null, true, Instant.now());
		redisTemplate.opsForValue().set(MANUAL_KEY_PREFIX + userId, objectMapper.writeValueAsString(response),
				properties.getManualStatusTtl());
		statusPublisher.publish(userId, response);
	}

	void clearManualStatus(UUID userId) {
		redisTemplate.delete(MANUAL_KEY_PREFIX + userId);
		// Gdy pod spodem jest świeży status z providera, to on staje się aktualny —
		// "przestał słuchać" wysyłamy tylko wtedy, gdy nie zostało nic do pokazania.
		String providerCached = redisTemplate.opsForValue().get(CACHE_KEY_PREFIX + userId);
		if (providerCached == null || CACHE_NOTHING_PLAYING.equals(providerCached)) {
			statusPublisher.publishStopped(userId);
		}
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
		Set<UUID> friendIds = sharingUserIds(socialGraph.friendIds(userId));
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
		if (candidates.isEmpty()) {
			return Set.of();
		}
		// Kto wyłączył widoczność, tego nie ma sensu (ani prawa) odpytywać — jego
		// status i tak nie opuściłby serwera.
		return sharingUserIds(linkRepository.findUserIdsByProviderAndUserIdIn(musicProvider.name(), candidates));
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
		Optional<NowPlayingStatusResponse> fresh = fetchAndCache(userId);
		if (fresh.isEmpty()) {
			if (previous != null && !CACHE_NOTHING_PLAYING.equals(previous)) {
				statusPublisher.publishStopped(userId);
			}
			return;
		}
		if (isDifferentTrack(previous, fresh.get())) {
			statusPublisher.publish(userId, fresh.get());
		}
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
