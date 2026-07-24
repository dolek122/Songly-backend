package com.songly.nowplaying;

import com.songly.nowplaying.dto.FriendNowPlayingResponse;
import com.songly.profile.ProfileDirectory;
import com.songly.social.SocialGraph;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Most między zmianą statusu a strumieniami SSE (ADR 0007, Etap 1): na
 * {@link NowPlayingStatusChangedEvent} pushuje nowy status do połączonych
 * <b>znajomych</b> użytkownika, którego status się zmienił.
 *
 * <p>
 * Prywatność (Claude.md §9): odbiorcy to znajomi liczeni <b>w momencie
 * zdarzenia</b> (relacja mogła się zmienić od otwarcia strumienia), nigdy obcy
 * ani zablokowani — {@link SocialGraph#friendIds} zwraca wyłącznie
 * zaakceptowane znajomości.
 */
@Component
class NowPlayingStreamBroadcaster {

	private static final String EVENT_NAME = "now-playing";

	private final NowPlayingStreamHub hub;
	private final SocialGraph socialGraph;
	private final ProfileDirectory profileDirectory;

	NowPlayingStreamBroadcaster(NowPlayingStreamHub hub, SocialGraph socialGraph, ProfileDirectory profileDirectory) {
		this.hub = hub;
		this.socialGraph = socialGraph;
		this.profileDirectory = profileDirectory;
	}

	@EventListener
	void onStatusChanged(NowPlayingStatusChangedEvent event) {
		UUID subjectId = event.userId();
		// Tylko znajomi, którzy naprawdę słuchają (mają otwarty strumień) — bez tego
		// filtra liczylibyśmy profil i iterowali po znajomych bez żadnego odbiorcy.
		Set<UUID> listeningFriends = socialGraph.friendIds(subjectId).stream().filter(hub::hasSubscribers)
				.collect(Collectors.toSet());
		if (listeningFriends.isEmpty()) {
			return;
		}
		Map<UUID, String> names = profileDirectory.displayNames(Set.of(subjectId));
		Map<UUID, String> avatars = profileDirectory.avatarUrls(Set.of(subjectId));
		FriendNowPlayingResponse payload = new FriendNowPlayingResponse(subjectId, names.get(subjectId),
				avatars.get(subjectId), event.status());
		for (UUID friendId : listeningFriends) {
			hub.push(friendId, EVENT_NAME, payload);
		}
	}
}
