package com.songly.nowplaying;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.songly.nowplaying.dto.FriendNowPlayingResponse;
import com.songly.nowplaying.dto.NowPlayingStatusResponse;
import com.songly.profile.ProfileDirectory;
import com.songly.social.SocialGraph;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NowPlayingStreamBroadcasterTest {

	@Mock
	private NowPlayingStreamHub hub;

	@Mock
	private SocialGraph socialGraph;

	@Mock
	private ProfileDirectory profileDirectory;

	@Mock
	private NowPlayingService nowPlayingService;

	@InjectMocks
	private NowPlayingStreamBroadcaster broadcaster;

	private static NowPlayingStatusResponse status() {
		return new NowPlayingStatusResponse("Tidal", "Bohemian Rhapsody", "Queen", null, null, true, Instant.now());
	}

	@Test
	void pushesToConnectedFriendsOnly_withSubjectProfileInPayload() {
		UUID subject = UUID.randomUUID();
		UUID connectedFriend = UUID.randomUUID();
		UUID offlineFriend = UUID.randomUUID();
		NowPlayingStatusResponse status = status();
		when(hub.hasAnySubscriber()).thenReturn(true);
		when(nowPlayingService.sharesStatus(subject)).thenReturn(true);
		when(socialGraph.friendIds(subject)).thenReturn(Set.of(connectedFriend, offlineFriend));
		when(hub.hasSubscribers(connectedFriend)).thenReturn(true);
		when(hub.hasSubscribers(offlineFriend)).thenReturn(false);
		when(profileDirectory.displayNames(Set.of(subject))).thenReturn(Map.of(subject, "Ada"));
		when(profileDirectory.avatarUrls(Set.of(subject))).thenReturn(Map.of(subject, "http://a/ada.png"));

		broadcaster.broadcast(new NowPlayingStatusChangedMessage(subject, status));

		ArgumentCaptor<FriendNowPlayingResponse> payload = ArgumentCaptor.forClass(FriendNowPlayingResponse.class);
		verify(hub).push(eq(connectedFriend), eq("now-playing"), payload.capture());
		verify(hub, never()).push(eq(offlineFriend), any(), any());
		assertThat(payload.getValue().userId()).isEqualTo(subject);
		assertThat(payload.getValue().displayName()).isEqualTo("Ada");
		assertThat(payload.getValue().avatarUrl()).isEqualTo("http://a/ada.png");
		assertThat(payload.getValue().status()).isEqualTo(status);
	}

	@Test
	void noPushNorProfileLookup_whenSubjectHasNoFriends() {
		UUID subject = UUID.randomUUID();
		when(hub.hasAnySubscriber()).thenReturn(true);
		when(nowPlayingService.sharesStatus(subject)).thenReturn(true);
		when(socialGraph.friendIds(subject)).thenReturn(Set.of());

		broadcaster.broadcast(new NowPlayingStatusChangedMessage(subject, status()));

		verifyNoInteractions(profileDirectory);
		verify(hub, never()).push(any(), any(), any());
	}

	@Test
	void noPushNorProfileLookup_whenNoFriendIsConnected() {
		UUID subject = UUID.randomUUID();
		UUID friend = UUID.randomUUID();
		when(hub.hasAnySubscriber()).thenReturn(true);
		when(nowPlayingService.sharesStatus(subject)).thenReturn(true);
		when(socialGraph.friendIds(subject)).thenReturn(Set.of(friend));
		when(hub.hasSubscribers(friend)).thenReturn(false);

		broadcaster.broadcast(new NowPlayingStatusChangedMessage(subject, status()));

		verifyNoInteractions(profileDirectory);
		verify(hub, never()).push(any(), any(), any());
	}

	/**
	 * Fan-out z Etapu 2 dowozi każdą zmianę na każdą instancję — ta bez ani jednego
	 * strumienia nie może za to płacić zapytaniem o graf znajomych.
	 */
	@Test
	void dropsMessage_withoutTouchingGraph_whenInstanceHoldsNoStreams() {
		when(hub.hasAnySubscriber()).thenReturn(false);

		broadcaster.broadcast(new NowPlayingStatusChangedMessage(UUID.randomUUID(), status()));

		verifyNoInteractions(socialGraph, profileDirectory);
		verify(hub, never()).push(any(), any(), any());
	}

	/**
	 * Widoczność egzekwowana przy pushu, nie przy otwarciu strumienia (ADR 0007,
	 * Etap 4): wiadomość mogła wyjść z instancji, która o wyłączeniu jeszcze nie
	 * wiedziała.
	 */
	@Test
	void dropsMessage_whenSubjectTurnedSharingOff() {
		UUID subject = UUID.randomUUID();
		when(hub.hasAnySubscriber()).thenReturn(true);
		when(nowPlayingService.sharesStatus(subject)).thenReturn(false);

		broadcaster.broadcast(new NowPlayingStatusChangedMessage(subject, status()));

		verifyNoInteractions(socialGraph, profileDirectory);
		verify(hub, never()).push(any(), any(), any());
	}
}
