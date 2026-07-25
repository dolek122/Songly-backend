package com.songly.nowplaying;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.songly.social.SocialGraph;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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

	@InjectMocks
	private NowPlayingPoller poller;

	@Test
	void refreshesFriendsOfConnectedViewers_deduplicatingSharedFriends() {
		UUID viewerA = UUID.randomUUID();
		UUID viewerB = UUID.randomUUID();
		UUID sharedFriend = UUID.randomUUID();
		UUID onlyBsFriend = UUID.randomUUID();
		when(hub.connectedUserIds()).thenReturn(Set.of(viewerA, viewerB));
		when(socialGraph.friendIds(viewerA)).thenReturn(Set.of(sharedFriend));
		when(socialGraph.friendIds(viewerB)).thenReturn(Set.of(sharedFriend, onlyBsFriend));
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
		when(nowPlayingService.pollableUserIds(Set.of(linkedFriend, unlinkedFriend))).thenReturn(Set.of(linkedFriend));

		poller.pollObservedUsers();

		verify(nowPlayingService).refreshFromProvider(linkedFriend);
		verify(nowPlayingService, never()).refreshFromProvider(unlinkedFriend);
	}

	@Test
	void continuesWithNextUser_whenOneRefreshFails() {
		UUID viewer = UUID.randomUUID();
		UUID failing = UUID.randomUUID();
		UUID healthy = UUID.randomUUID();
		when(hub.connectedUserIds()).thenReturn(Set.of(viewer));
		when(socialGraph.friendIds(viewer)).thenReturn(Set.of(failing, healthy));
		when(nowPlayingService.pollableUserIds(any())).thenReturn(new LinkedHashSet<>(List.of(failing, healthy)));
		doThrow(new SpotifyApiException(503)).when(nowPlayingService).refreshFromProvider(failing);

		poller.pollObservedUsers();

		verify(nowPlayingService).refreshFromProvider(healthy);
	}
}
