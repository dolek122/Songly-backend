package com.songly.nowplaying;

import com.songly.identity.SuspendedAccounts;
import com.songly.nowplaying.dto.FriendNowPlayingResponse;
import com.songly.nowplaying.dto.ManualNowPlayingRequest;
import com.songly.nowplaying.dto.NowPlayingSettingsResponse;
import com.songly.nowplaying.dto.NowPlayingStatusResponse;
import com.songly.nowplaying.dto.UpdateNowPlayingSettingsRequest;
import com.songly.profile.ProfileDirectory;
import com.songly.social.SocialGraph;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Service
@EnableConfigurationProperties(NowPlayingProperties.class)
class NowPlayingService {

	private static final Logger log = LoggerFactory.getLogger(NowPlayingService.class);

	private static final String STATE_KEY_PREFIX = "now-playing:oauth-state:";
	private static final String TICKET_KEY_PREFIX = "now-playing:oauth-ticket:";
	private static final String CACHE_KEY_PREFIX = "now-playing:status:";
	private static final String MANUAL_KEY_PREFIX = "now-playing:manual:";
	private static final String POLL_LOCK_KEY_PREFIX = "now-playing:poll-lock:";
	private static final String PROVIDER_BACKOFF_KEY = "now-playing:provider-backoff";
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
	private final SuspendedAccounts suspendedAccounts;
	private final SecureRandom secureRandom = new SecureRandom();

	NowPlayingService(MusicProvider musicProvider, MusicProviderLinkRepository linkRepository,
			NowPlayingPreferenceRepository preferenceRepository, StringRedisTemplate redisTemplate,
			ObjectMapper objectMapper, NowPlayingProperties properties, SocialGraph socialGraph,
			ProfileDirectory profileDirectory, NowPlayingStatusPublisher statusPublisher,
			SuspendedAccounts suspendedAccounts) {
		this.musicProvider = musicProvider;
		this.linkRepository = linkRepository;
		this.preferenceRepository = preferenceRepository;
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
		this.properties = properties;
		this.socialGraph = socialGraph;
		this.profileDirectory = profileDirectory;
		this.statusPublisher = statusPublisher;
		this.suspendedAccounts = suspendedAccounts;
	}

	String beginConnect(UUID userId) {
		String state = randomToken();
		redisTemplate.opsForValue().set(STATE_KEY_PREFIX + state, userId.toString(), properties.getOauthStateTtl());
		return musicProvider.buildAuthorizeUrl(state);
	}

	String acceptAuthorization(String state, String authorizationCode) {
		String userId = redisTemplate.opsForValue().getAndDelete(STATE_KEY_PREFIX + state);
		if (userId == null) {
			throw new InvalidOAuthStateException();
		}
		String ticket = randomToken();
		redisTemplate.opsForValue().set(TICKET_KEY_PREFIX + ticket,
				objectMapper.writeValueAsString(new PendingConnection(userId, authorizationCode)),
				properties.getOauthTicketTtl());
		return ticket;
	}

	void completeConnect(UUID userId, String ticket) {
		String value = redisTemplate.opsForValue().getAndDelete(TICKET_KEY_PREFIX + ticket);
		if (value == null) {
			throw new InvalidConnectTicketException();
		}
		PendingConnection pending = objectMapper.readValue(value, PendingConnection.class);
		if (!userId.toString().equals(pending.userId())) {
			log.warn("Bilet połączenia Spotify z przepływu innego konta: flowOwner={}, caller={}", pending.userId(),
					userId);
			throw new InvalidConnectTicketException();
		}
		musicProvider.connect(userId, pending.code());
	}

	private record PendingConnection(String userId, String code) {
	}

	private String randomToken() {
		byte[] bytes = new byte[24];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	void abandonConnect(String state) {
		redisTemplate.delete(STATE_KEY_PREFIX + state);
	}

	@Transactional
	void disconnect(UUID userId) {
		linkRepository.deleteByUserIdAndProvider(userId, musicProvider.name());
		redisTemplate.delete(CACHE_KEY_PREFIX + userId);
		if (!Boolean.TRUE.equals(redisTemplate.hasKey(MANUAL_KEY_PREFIX + userId))) {
			statusPublisher.publishStopped(userId);
		}
	}

	@Transactional
	void deleteAccountData(UUID userId) {
		linkRepository.deleteByUserId(userId);
		preferenceRepository.deleteByUserId(userId);
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					forgetLiveState(userId);
				}
			});
		} else {
			forgetLiveState(userId);
		}
	}

	private void forgetLiveState(UUID userId) {
		try {
			redisTemplate.delete(CACHE_KEY_PREFIX + userId);
			redisTemplate.delete(MANUAL_KEY_PREFIX + userId);
			statusPublisher.publishStopped(userId);
		} catch (RuntimeException ex) {
			log.warn("Nie udało się wyczyścić statusu usuniętego konta {} w Redisie — wygaśnie sam: {}", userId,
					ex.toString());
		}
	}

	@Transactional(readOnly = true)
	NowPlayingSettingsResponse getSettings(UUID userId) {
		return new NowPlayingSettingsResponse(!preferenceRepository.existsByUserIdAndShareWithFriendsFalse(userId));
	}

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

	@Transactional(readOnly = true)
	boolean sharesStatus(UUID userId) {
		return !preferenceRepository.existsByUserIdAndShareWithFriendsFalse(userId)
				&& !suspendedAccounts.suspendedUserIds().contains(userId);
	}

	@Transactional(readOnly = true)
	Set<UUID> sharingUserIds(Set<UUID> candidates) {
		if (candidates.isEmpty()) {
			return Set.of();
		}
		Set<UUID> optedOut = preferenceRepository.findOptedOutUserIds(candidates);
		Set<UUID> suspended = suspendedAccounts.suspendedUserIds();
		return candidates.stream().filter(userId -> !optedOut.contains(userId) && !suspended.contains(userId))
				.collect(Collectors.toSet());
	}

	void publishStoppedIfStatusExpired(UUID userId) {
		if (!Boolean.TRUE.equals(redisTemplate.hasKey(NowPlayingStatusPublisher.ANNOUNCED_KEY_PREFIX + userId))) {
			return;
		}
		if (getCachedStatus(userId).isPresent()) {
			return;
		}
		statusPublisher.publishStopped(userId);
	}

	boolean tryAcquirePollLock(UUID userId) {
		return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(POLL_LOCK_KEY_PREFIX + userId, "1",
				properties.getPoll().getLockTtl()));
	}

	boolean isProviderBackoffActive() {
		return Boolean.TRUE.equals(redisTemplate.hasKey(PROVIDER_BACKOFF_KEY));
	}

	void applyProviderBackoff(Duration duration) {
		redisTemplate.opsForValue().set(PROVIDER_BACKOFF_KEY, "1", duration);
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
		String providerCached = redisTemplate.opsForValue().get(CACHE_KEY_PREFIX + userId);
		if (providerCached == null || CACHE_NOTHING_PLAYING.equals(providerCached)) {
			statusPublisher.publishStopped(userId);
		}
	}

	@Transactional(readOnly = true)
	List<FriendNowPlayingResponse> friendsNowPlaying(UUID userId) {
		Set<UUID> friendIds = sharingUserIds(socialGraph.friendIds(userId));
		if (friendIds.isEmpty()) {
			return List.of();
		}
		Map<UUID, String> names = profileDirectory.displayNames(friendIds);
		Map<UUID, String> avatars = profileDirectory.avatarUrls(friendIds);
		Map<UUID, NowPlayingStatusResponse> statuses = cachedStatuses(List.copyOf(friendIds));
		return statuses.entrySet().stream()
				.map(entry -> new FriendNowPlayingResponse(entry.getKey(), names.get(entry.getKey()),
						avatars.get(entry.getKey()), entry.getValue()))
				.sorted(Comparator.comparing(FriendNowPlayingResponse::displayName,
						Comparator.nullsLast(Comparator.naturalOrder())))
				.toList();
	}

	private Map<UUID, NowPlayingStatusResponse> cachedStatuses(List<UUID> userIds) {
		List<String> manualRaw = redisTemplate.opsForValue()
				.multiGet(userIds.stream().map(id -> MANUAL_KEY_PREFIX + id).toList());
		List<String> providerRaw = redisTemplate.opsForValue()
				.multiGet(userIds.stream().map(id -> CACHE_KEY_PREFIX + id).toList());
		Map<UUID, NowPlayingStatusResponse> statuses = new LinkedHashMap<>();
		for (int i = 0; i < userIds.size(); i++) {
			String manual = valueAt(manualRaw, i);
			String provider = valueAt(providerRaw, i);
			String raw = manual != null ? manual : provider;
			if (raw == null || CACHE_NOTHING_PLAYING.equals(raw)) {
				continue;
			}
			statuses.put(userIds.get(i), objectMapper.readValue(raw, NowPlayingStatusResponse.class));
		}
		return statuses;
	}

	private String valueAt(List<String> values, int index) {
		return values == null || index >= values.size() ? null : values.get(index);
	}

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
		if (!linkRepository.existsByUserIdAndProvider(userId, musicProvider.name())) {
			return Optional.empty();
		}
		return fetchAndCache(userId);
	}

	@Transactional(readOnly = true)
	Set<UUID> pollableUserIds(Set<UUID> candidates) {
		if (candidates.isEmpty()) {
			return Set.of();
		}
		return sharingUserIds(linkRepository.findUserIdsByProviderAndUserIdIn(musicProvider.name(), candidates));
	}

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

	private boolean isDifferentTrack(String previousJson, NowPlayingStatusResponse fresh) {
		if (previousJson == null || CACHE_NOTHING_PLAYING.equals(previousJson)) {
			return true;
		}
		NowPlayingStatusResponse previous = objectMapper.readValue(previousJson, NowPlayingStatusResponse.class);
		return !Objects.equals(previous.title(), fresh.title()) || !Objects.equals(previous.artist(), fresh.artist())
				|| !Objects.equals(previous.album(), fresh.album()) || previous.playing() != fresh.playing();
	}
}
