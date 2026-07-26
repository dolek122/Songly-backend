package com.songly.nowplaying;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.songly.social.SocialGraph;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NowPlayingPollerTest {

	@Mock
	private NowPlayingStreamHub hub;

	@Mock
	private SocialGraph socialGraph;

	@Mock
	private NowPlayingService nowPlayingService;

	private final NowPlayingProperties properties = new NowPlayingProperties();
	private NowPlayingPoller poller;

	@BeforeEach
	void setUp() {
		poller = new NowPlayingPoller(hub, socialGraph, nowPlayingService, properties);
	}

	private static SpotifyApiException spotifyError(int status) {
		return new SpotifyApiException(status, null, null);
	}

	private static SpotifyApiException rateLimited(Duration retryAfter) {
		return new SpotifyApiException(429, null, retryAfter);
	}

	@Test
	void refreshesFriendsOfConnectedViewers_deduplicatingSharedFriends() {
		UUID viewerA = UUID.randomUUID();
		UUID viewerB = UUID.randomUUID();
		UUID sharedFriend = UUID.randomUUID();
		UUID onlyBsFriend = UUID.randomUUID();
		when(hub.connectedUserIds()).thenReturn(Set.of(viewerA, viewerB));
		when(socialGraph.friendIds(viewerA)).thenReturn(Set.of(sharedFriend));
		when(socialGraph.friendIds(viewerB)).thenReturn(Set.of(sharedFriend, onlyBsFriend));
		when(nowPlayingService.tryAcquirePollLock(any())).thenReturn(true);
		when(nowPlayingService.pollableUserIds(Set.of(sharedFriend, onlyBsFriend)))
				.thenReturn(Set.of(sharedFriend, onlyBsFriend));

		poller.pollObservedUsers();

		verify(nowPlayingService).refreshFromProvider(sharedFriend);
		verify(nowPlayingService).refreshFromProvider(onlyBsFriend);
	}

	@Test
	void doesNotTouchProvider_whenNobodyIsWatching() {
		when(hub.connectedUserIds()).thenReturn(Set.of());

		poller.pollObservedUsers();

		verifyNoInteractions(socialGraph, nowPlayingService);
	}

	@Test
	void doesNotTouchProvider_whenViewersHaveNoFriends() {
		UUID viewer = UUID.randomUUID();
		when(hub.connectedUserIds()).thenReturn(Set.of(viewer));
		when(socialGraph.friendIds(viewer)).thenReturn(Set.of());

		poller.pollObservedUsers();

		verifyNoInteractions(nowPlayingService);
	}

	@Test
	void skipsUsersWithoutProviderLink() {
		UUID viewer = UUID.randomUUID();
		UUID linkedFriend = UUID.randomUUID();
		UUID unlinkedFriend = UUID.randomUUID();
		when(hub.connectedUserIds()).thenReturn(Set.of(viewer));
		when(socialGraph.friendIds(viewer)).thenReturn(Set.of(linkedFriend, unlinkedFriend));
		when(nowPlayingService.tryAcquirePollLock(linkedFriend)).thenReturn(true);
		when(nowPlayingService.pollableUserIds(Set.of(linkedFriend, unlinkedFriend))).thenReturn(Set.of(linkedFriend));

		poller.pollObservedUsers();

		verify(nowPlayingService).refreshFromProvider(linkedFriend);
		verify(nowPlayingService, never()).refreshFromProvider(unlinkedFriend);
	}

	/**
	 * Wygasły status wykrywamy u <b>wszystkich</b> obserwowanych, także tych bez
	 * providera — to właśnie oni ustawiają status ręcznie i nie mają czego
	 * odpytywać (ADR 0007).
	 */
	@Test
	void checksExpiredStatus_alsoForUsersWithoutProviderLink() {
		UUID viewer = UUID.randomUUID();
		UUID linkedFriend = UUID.randomUUID();
		UUID unlinkedFriend = UUID.randomUUID();
		when(hub.connectedUserIds()).thenReturn(Set.of(viewer));
		when(socialGraph.friendIds(viewer)).thenReturn(Set.of(linkedFriend, unlinkedFriend));
		when(nowPlayingService.tryAcquirePollLock(linkedFriend)).thenReturn(true);
		when(nowPlayingService.pollableUserIds(Set.of(linkedFriend, unlinkedFriend))).thenReturn(Set.of(linkedFriend));

		poller.pollObservedUsers();

		verify(nowPlayingService).publishStoppedIfStatusExpired(linkedFriend);
		verify(nowPlayingService).publishStoppedIfStatusExpired(unlinkedFriend);
	}

	/**
	 * Padnięte zapytanie o listę do odpytania nie może zabrać sprawdzenia wygasłych
	 * statusów — ono nie zależy od providera ani od tej listy.
	 */
	@Test
	void expiryCheckStillRuns_whenPollableLookupFails() {
		UUID viewer = UUID.randomUUID();
		UUID friend = UUID.randomUUID();
		when(hub.connectedUserIds()).thenReturn(Set.of(viewer));
		when(socialGraph.friendIds(viewer)).thenReturn(Set.of(friend));
		when(nowPlayingService.pollableUserIds(any())).thenThrow(new IllegalStateException("baza nie odpowiada"));

		poller.pollObservedUsers();

		verify(nowPlayingService).publishStoppedIfStatusExpired(friend);
	}

	@Test
	void failedPollLock_doesNotStopTheRest() {
		UUID viewer = UUID.randomUUID();
		UUID failing = UUID.randomUUID();
		UUID healthy = UUID.randomUUID();
		when(hub.connectedUserIds()).thenReturn(Set.of(viewer));
		when(socialGraph.friendIds(viewer)).thenReturn(new LinkedHashSet<>(List.of(failing, healthy)));
		when(nowPlayingService.pollableUserIds(any())).thenReturn(new LinkedHashSet<>(List.of(failing, healthy)));
		when(nowPlayingService.tryAcquirePollLock(failing)).thenThrow(new IllegalStateException("Redis padł"));
		when(nowPlayingService.tryAcquirePollLock(healthy)).thenReturn(true);

		poller.pollObservedUsers();

		verify(nowPlayingService).refreshFromProvider(healthy);
	}

	@Test
	void oneFailedExpiryCheck_doesNotStopTheRest() {
		UUID viewer = UUID.randomUUID();
		UUID failing = UUID.randomUUID();
		UUID healthy = UUID.randomUUID();
		when(hub.connectedUserIds()).thenReturn(Set.of(viewer));
		when(socialGraph.friendIds(viewer)).thenReturn(new LinkedHashSet<>(List.of(failing, healthy)));
		when(nowPlayingService.pollableUserIds(any())).thenReturn(Set.of());
		doThrow(spotifyError(503)).when(nowPlayingService).publishStoppedIfStatusExpired(failing);

		poller.pollObservedUsers();

		verify(nowPlayingService).publishStoppedIfStatusExpired(healthy);
	}

	@Test
	void continuesWithNextUser_whenOneRefreshFails() {
		UUID viewer = UUID.randomUUID();
		UUID failing = UUID.randomUUID();
		UUID healthy = UUID.randomUUID();
		when(hub.connectedUserIds()).thenReturn(Set.of(viewer));
		when(socialGraph.friendIds(viewer)).thenReturn(Set.of(failing, healthy));
		when(nowPlayingService.tryAcquirePollLock(any())).thenReturn(true);
		when(nowPlayingService.pollableUserIds(any())).thenReturn(new LinkedHashSet<>(List.of(failing, healthy)));
		doThrow(spotifyError(503)).when(nowPlayingService).refreshFromProvider(failing);

		poller.pollObservedUsers();

		verify(nowPlayingService).refreshFromProvider(healthy);
	}

	/**
	 * Rate limit Spotify jest per client ID, więc kolejni użytkownicy w tym samym
	 * przebiegu dostaliby to samo 429 — dopytywanie tylko pogłębiłoby karę. Stąd
	 * przerwanie przebiegu, inaczej niż przy zwykłym błędzie providera wyżej.
	 */
	@Test
	void rateLimit_abortsRunAndBacksOffForRetryAfter() {
		UUID viewer = UUID.randomUUID();
		UUID rateLimited = UUID.randomUUID();
		UUID next = UUID.randomUUID();
		when(hub.connectedUserIds()).thenReturn(Set.of(viewer));
		when(socialGraph.friendIds(viewer)).thenReturn(new LinkedHashSet<>(List.of(rateLimited, next)));
		when(nowPlayingService.pollableUserIds(any())).thenReturn(new LinkedHashSet<>(List.of(rateLimited, next)));
		when(nowPlayingService.tryAcquirePollLock(rateLimited)).thenReturn(true);
		doThrow(rateLimited(Duration.ofSeconds(90))).when(nowPlayingService).refreshFromProvider(rateLimited);

		poller.pollObservedUsers();

		verify(nowPlayingService).applyProviderBackoff(Duration.ofSeconds(90));
		verify(nowPlayingService, never()).refreshFromProvider(next);
		// Zamiatanie wygasłych statusów nie dotyka providera, więc biegnie dalej.
		verify(nowPlayingService).publishStoppedIfStatusExpired(next);
	}

	@Test
	void rateLimitWithoutRetryAfter_usesConfiguredBackoff() {
		UUID viewer = UUID.randomUUID();
		UUID friend = UUID.randomUUID();
		properties.getPoll().setProviderBackoff(Duration.ofMinutes(5));
		when(hub.connectedUserIds()).thenReturn(Set.of(viewer));
		when(socialGraph.friendIds(viewer)).thenReturn(Set.of(friend));
		when(nowPlayingService.pollableUserIds(any())).thenReturn(Set.of(friend));
		when(nowPlayingService.tryAcquirePollLock(friend)).thenReturn(true);
		doThrow(rateLimited(null)).when(nowPlayingService).refreshFromProvider(friend);

		poller.pollObservedUsers();

		verify(nowPlayingService).applyProviderBackoff(Duration.ofMinutes(5));
	}

	@Test
	void skipsProviderEntirely_whileBackoffIsActive() {
		UUID viewer = UUID.randomUUID();
		UUID friend = UUID.randomUUID();
		when(hub.connectedUserIds()).thenReturn(Set.of(viewer));
		when(socialGraph.friendIds(viewer)).thenReturn(Set.of(friend));
		when(nowPlayingService.isProviderBackoffActive()).thenReturn(true);

		poller.pollObservedUsers();

		verify(nowPlayingService, never()).pollableUserIds(any());
		verify(nowPlayingService, never()).refreshFromProvider(any());
		verify(nowPlayingService).publishStoppedIfStatusExpired(friend);
	}
}
