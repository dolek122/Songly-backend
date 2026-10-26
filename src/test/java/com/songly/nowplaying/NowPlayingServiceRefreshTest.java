package com.songly.nowplaying;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.songly.identity.SuspendedAccounts;
import com.songly.nowplaying.dto.NowPlayingStatusResponse;
import com.songly.nowplaying.dto.UpdateNowPlayingSettingsRequest;
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
	private static final String ANNOUNCED_KEY_PREFIX = "now-playing:announced:";

	@Mock
	private MusicProvider musicProvider;

	@Mock
	private MusicProviderLinkRepository linkRepository;

	@Mock
	private NowPlayingPreferenceRepository preferenceRepository;

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	@Mock
	private SocialGraph socialGraph;

	@Mock
	private ProfileDirectory profileDirectory;

	@Mock
	private NowPlayingStatusPublisher statusPublisher;

	@Mock
	private SuspendedAccounts suspendedAccounts;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final NowPlayingProperties properties = new NowPlayingProperties();
	private final UUID userId = UUID.randomUUID();

	private NowPlayingService service;

	@BeforeEach
	void setUp() {
		service = new NowPlayingService(musicProvider, linkRepository, preferenceRepository, redisTemplate,
				objectMapper, properties, socialGraph, profileDirectory, statusPublisher, suspendedAccounts);
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
		ArgumentCaptor<NowPlayingStatusResponse> published = ArgumentCaptor.forClass(NowPlayingStatusResponse.class);
		verify(statusPublisher).publish(eq(userId), published.capture());
		assertThat(published.getValue().title()).isEqualTo("Love of My Life");
	}

	@Test
	void publishesEvent_whenNothingWasCachedBefore() {
		when(redisTemplate.hasKey(MANUAL_KEY_PREFIX + userId)).thenReturn(false);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(CACHE_KEY_PREFIX + userId)).thenReturn(null);
		provider("Bohemian Rhapsody");

		service.refreshFromProvider(userId);

		verify(statusPublisher).publish(eq(userId), any(NowPlayingStatusResponse.class));
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
		verify(statusPublisher, never()).publish(any(), any());
		verify(statusPublisher, never()).publishStopped(any());
	}

	@Test
	void cachesNothingPlaying_whenProviderReturnsNothing() {
		when(redisTemplate.hasKey(MANUAL_KEY_PREFIX + userId)).thenReturn(false);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(CACHE_KEY_PREFIX + userId)).thenReturn(cachedJson("Bohemian Rhapsody"));
		when(musicProvider.getCurrentlyPlaying(userId)).thenReturn(Optional.empty());

		service.refreshFromProvider(userId);

		// Sentinel w cache'u, żeby kolejny odczyt nie poszedł znowu do providera
		verify(valueOperations).set(CACHE_KEY_PREFIX + userId, "NOTHING_PLAYING", Duration.ofSeconds(45));
	}

	/**
	 * "Przestał słuchać" (ADR 0007, Etap 4) — bez tego znajomy zostawałby na liście
	 * grających do wygaśnięcia cache'a.
	 */
	@Test
	void publishesStopped_whenPlaybackEnded() {
		when(redisTemplate.hasKey(MANUAL_KEY_PREFIX + userId)).thenReturn(false);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(CACHE_KEY_PREFIX + userId)).thenReturn(cachedJson("Bohemian Rhapsody"));
		when(musicProvider.getCurrentlyPlaying(userId)).thenReturn(Optional.empty());

		service.refreshFromProvider(userId);

		verify(statusPublisher).publishStopped(userId);
	}

	@Test
	void doesNotRepeatStopped_whenNothingWasPlayingBefore() {
		when(redisTemplate.hasKey(MANUAL_KEY_PREFIX + userId)).thenReturn(false);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(CACHE_KEY_PREFIX + userId)).thenReturn("NOTHING_PLAYING");
		when(musicProvider.getCurrentlyPlaying(userId)).thenReturn(Optional.empty());

		service.refreshFromProvider(userId);

		verify(statusPublisher, never()).publish(any(), any());
		verify(statusPublisher, never()).publishStopped(any());
	}

	@Test
	void clearingManualStatus_publishesStopped_whenNothingElseIsPlaying() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(CACHE_KEY_PREFIX + userId)).thenReturn(null);

		service.clearManualStatus(userId);

		verify(statusPublisher).publishStopped(userId);
	}

	/**
	 * Ręczny status zdjęty z wierzchu odsłania świeży status z providera — wtedy
	 * użytkownik nadal coś gra i "przestał słuchać" byłoby kłamstwem.
	 */
	@Test
	void clearingManualStatus_staysQuiet_whenProviderStatusIsStillCached() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(CACHE_KEY_PREFIX + userId)).thenReturn(cachedJson("Bohemian Rhapsody"));

		service.clearManualStatus(userId);

		verify(statusPublisher, never()).publish(any(), any());
		verify(statusPublisher, never()).publishStopped(any());
	}

	@Test
	void skipsProviderEntirely_whenManualStatusIsSet() {
		when(redisTemplate.hasKey(MANUAL_KEY_PREFIX + userId)).thenReturn(true);

		service.refreshFromProvider(userId);

		verifyNoInteractions(musicProvider, statusPublisher);
	}

	@Test
	void pollableUserIds_asksRepositoryOnceForWholeBatch() {
		Set<UUID> candidates = Set.of(userId, UUID.randomUUID());
		when(musicProvider.name()).thenReturn("spotify");
		when(linkRepository.findUserIdsByProviderAndUserIdIn("spotify", candidates)).thenReturn(Set.of(userId));
		when(preferenceRepository.findOptedOutUserIds(Set.of(userId))).thenReturn(Set.of());

		assertThat(service.pollableUserIds(candidates)).containsExactly(userId);
	}

	@Test
	void pollableUserIds_shortCircuitsOnEmptyInput() {
		assertThat(service.pollableUserIds(Set.of())).isEmpty();
		verifyNoInteractions(linkRepository);
	}

	/**
	 * Kto wyłączył widoczność, tego nie ma po co odpytywać — jego status i tak nie
	 * opuściłby serwera (ADR 0007, Etap 4).
	 */
	@Test
	void pollableUserIds_skipsUsersWhoTurnedSharingOff() {
		UUID hidden = UUID.randomUUID();
		Set<UUID> candidates = Set.of(userId, hidden);
		when(musicProvider.name()).thenReturn("spotify");
		when(linkRepository.findUserIdsByProviderAndUserIdIn("spotify", candidates)).thenReturn(candidates);
		when(preferenceRepository.findOptedOutUserIds(candidates)).thenReturn(Set.of(hidden));

		assertThat(service.pollableUserIds(candidates)).containsExactly(userId);
	}

	@Test
	void sharesStatus_defaultsToTrue_whenUserNeverTouchedSettings() {
		when(preferenceRepository.existsByUserIdAndShareWithFriendsFalse(userId)).thenReturn(false);

		assertThat(service.sharesStatus(userId)).isTrue();
		assertThat(service.getSettings(userId).shareWithFriends()).isTrue();
	}

	@Test
	void sharesStatus_isFalse_afterOptOut() {
		when(preferenceRepository.existsByUserIdAndShareWithFriendsFalse(userId)).thenReturn(true);

		assertThat(service.sharesStatus(userId)).isFalse();
	}

	@Test
	void updateSettings_createsRow_whenUserHasNoneYet() {
		when(preferenceRepository.findById(userId)).thenReturn(Optional.empty());

		service.updateSettings(userId, new UpdateNowPlayingSettingsRequest(false));

		ArgumentCaptor<NowPlayingPreference> saved = ArgumentCaptor.forClass(NowPlayingPreference.class);
		verify(preferenceRepository).save(saved.capture());
		assertThat(saved.getValue().isShareWithFriends()).isFalse();
	}

	@Test
	void updateSettings_updatesExistingRow_withoutInsertingAnother() {
		NowPlayingPreference existing = new NowPlayingPreference(userId, false, Instant.now().minusSeconds(60));
		when(preferenceRepository.findById(userId)).thenReturn(Optional.of(existing));
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(MANUAL_KEY_PREFIX + userId)).thenReturn(null);
		when(valueOperations.get(CACHE_KEY_PREFIX + userId)).thenReturn(null);

		service.updateSettings(userId, new UpdateNowPlayingSettingsRequest(true));

		assertThat(existing.isShareWithFriends()).isTrue();
		verify(preferenceRepository, never()).save(any());
	}

	/**
	 * Sama flaga w bazie nie wystarcza: znajomy z otwartym strumieniem nie dostanie
	 * już żadnego pushu, więc bez tego widziałby ostatni status w nieskończoność.
	 */
	@Test
	void turningSharingOff_pushesStoppedToWatchingFriends() {
		when(preferenceRepository.findById(userId)).thenReturn(Optional.empty());

		service.updateSettings(userId, new UpdateNowPlayingSettingsRequest(false));

		verify(statusPublisher).publishStopped(userId);
	}

	@Test
	void turningSharingOn_pushesCurrentStatus_soFriendsSeeItWithoutWaitingForNextTrack() {
		when(preferenceRepository.findById(userId)).thenReturn(Optional.empty());
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(MANUAL_KEY_PREFIX + userId)).thenReturn(null);
		when(valueOperations.get(CACHE_KEY_PREFIX + userId)).thenReturn(cachedJson("Bohemian Rhapsody"));

		service.updateSettings(userId, new UpdateNowPlayingSettingsRequest(true));

		ArgumentCaptor<NowPlayingStatusResponse> published = ArgumentCaptor.forClass(NowPlayingStatusResponse.class);
		verify(statusPublisher).publish(eq(userId), published.capture());
		assertThat(published.getValue().title()).isEqualTo("Bohemian Rhapsody");
	}

	@Test
	void turningSharingOn_staysQuiet_whenNothingIsPlaying() {
		when(preferenceRepository.findById(userId)).thenReturn(Optional.empty());
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(MANUAL_KEY_PREFIX + userId)).thenReturn(null);
		when(valueOperations.get(CACHE_KEY_PREFIX + userId)).thenReturn(null);

		service.updateSettings(userId, new UpdateNowPlayingSettingsRequest(true));

		verify(statusPublisher, never()).publish(any(), any());
	}

	@Test
	void disconnectingProvider_pushesStopped() {
		when(musicProvider.name()).thenReturn("spotify");
		when(redisTemplate.hasKey(MANUAL_KEY_PREFIX + userId)).thenReturn(false);

		service.disconnect(userId);

		verify(statusPublisher).publishStopped(userId);
	}

	/** Ręczny status przeżywa odłączenie providera, więc user nadal coś gra. */
	@Test
	void disconnectingProvider_staysQuiet_whenManualStatusRemains() {
		when(musicProvider.name()).thenReturn("spotify");
		when(redisTemplate.hasKey(MANUAL_KEY_PREFIX + userId)).thenReturn(true);

		service.disconnect(userId);

		verify(statusPublisher, never()).publishStopped(any());
	}

	@Test
	void deletingAccount_pushesStopped_soFriendsStopSeeingAGhost() {
		service.deleteAccountData(userId);

		verify(statusPublisher).publishStopped(userId);
	}

	/**
	 * Ręczny status wygasa sam (TTL w godzinach), a jego właściciel nie ma
	 * providera do odpytania — bez tej ścieżki wisiałby na ekranie znajomego w
	 * nieskończoność.
	 */
	@Test
	void publishesStopped_whenAnnouncedStatusExpired() {
		when(redisTemplate.hasKey(ANNOUNCED_KEY_PREFIX + userId)).thenReturn(true);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(MANUAL_KEY_PREFIX + userId)).thenReturn(null);
		when(valueOperations.get(CACHE_KEY_PREFIX + userId)).thenReturn(null);

		service.publishStoppedIfStatusExpired(userId);

		verify(statusPublisher).publishStopped(userId);
	}

	@Test
	void staysQuiet_whenAnnouncedStatusIsStillThere() {
		when(redisTemplate.hasKey(ANNOUNCED_KEY_PREFIX + userId)).thenReturn(true);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(MANUAL_KEY_PREFIX + userId)).thenReturn(cachedJson("Bohemian Rhapsody"));

		service.publishStoppedIfStatusExpired(userId);

		verify(statusPublisher, never()).publishStopped(any());
	}

	/**
	 * Bez znacznika nie ma czego odwoływać — inaczej „stopped" leciałoby w kółko.
	 */
	@Test
	void staysQuiet_forUsersWhoNeverAnnouncedAnything() {
		when(redisTemplate.hasKey(ANNOUNCED_KEY_PREFIX + userId)).thenReturn(false);

		service.publishStoppedIfStatusExpired(userId);

		verifyNoInteractions(statusPublisher);
	}

	@Test
	void pollLock_isTakenByFirstCallerOnly() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent("now-playing:poll-lock:" + userId, "1", Duration.ofSeconds(25)))
				.thenReturn(true, false);

		assertThat(service.tryAcquirePollLock(userId)).isTrue();
		assertThat(service.tryAcquirePollLock(userId)).isFalse();
	}
}
