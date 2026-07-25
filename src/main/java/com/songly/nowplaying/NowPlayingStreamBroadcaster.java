package com.songly.nowplaying;

import com.songly.nowplaying.dto.FriendNowPlayingResponse;
import com.songly.profile.ProfileDirectory;
import com.songly.social.SocialGraph;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Most między zmianą statusu a strumieniami SSE (ADR 0007, Etapy 1–2): dla
 * {@link NowPlayingStatusChangedMessage} odebranej z kanału Redis pushuje nowy
 * status do połączonych <b>znajomych</b> użytkownika, którego status się
 * zmienił.
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
	private final NowPlayingService nowPlayingService;

	NowPlayingStreamBroadcaster(NowPlayingStreamHub hub, SocialGraph socialGraph, ProfileDirectory profileDirectory,
			NowPlayingService nowPlayingService) {
		this.hub = hub;
		this.socialGraph = socialGraph;
		this.profileDirectory = profileDirectory;
		this.nowPlayingService = nowPlayingService;
	}

	void broadcast(NowPlayingStatusChangedMessage event) {
		// Fan-out z Etapu 2 dowozi każdą zmianę na każdą instancję, także taką, na
		// której nikt nie ma otwartego strumienia — tam nie ma po co pytać o graf.
		if (!hub.hasAnySubscriber()) {
			return;
		}
		UUID subjectId = event.userId();
		// Widoczność sprawdzana przy każdym pushu, nie tylko przy otwarciu strumienia
		// (ADR 0007, Etap 4) — użytkownik mógł wyłączyć udostępnianie sekundę temu,
		// a wiadomość mogła wyjść z instancji, która jeszcze o tym nie wiedziała.
		if (!nowPlayingService.sharesStatus(subjectId)) {
			return;
		}
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
