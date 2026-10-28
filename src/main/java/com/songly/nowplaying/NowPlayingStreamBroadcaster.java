package com.songly.nowplaying;

import com.songly.identity.SuspendedAccounts;
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
	private final SuspendedAccounts suspendedAccounts;

	NowPlayingStreamBroadcaster(NowPlayingStreamHub hub, SocialGraph socialGraph, ProfileDirectory profileDirectory,
			NowPlayingService nowPlayingService, SuspendedAccounts suspendedAccounts) {
		this.hub = hub;
		this.socialGraph = socialGraph;
		this.profileDirectory = profileDirectory;
		this.nowPlayingService = nowPlayingService;
		this.suspendedAccounts = suspendedAccounts;
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
		//
		// "Przestał słuchać" (status == null) przechodzi zawsze: nic nie ujawnia,
		// tylko każe zdjąć wpis z listy. Leci m.in. przy wyłączeniu widoczności,
		// więc odrzucenie go tutaj zostawiłoby ostatni status na ekranie znajomego
		// dokładnie w chwili, w której miał zniknąć.
		if (event.status() != null && !nowPlayingService.sharesStatus(subjectId)) {
			return;
		}
		// Tylko znajomi, którzy naprawdę słuchają (mają otwarty strumień) — bez tego
		// filtra liczylibyśmy profil i iterowali po znajomych bez żadnego odbiorcy.
		Set<UUID> listeningFriends = socialGraph.friendIds(subjectId).stream().filter(hub::hasSubscribers)
				.collect(Collectors.toSet());
		if (listeningFriends.isEmpty()) {
			return;
		}
		// Zawieszony odbiorca z otwartym strumieniem nie dostaje już niczego (ADR
		// 0049). Strumień żyje do 30 minut, a zawieszenie ma działać od razu (ADR
		// 0022) — nowe żądania odcina ROLE_SUSPENDED, otwartego połączenia już nie.
		Set<UUID> suspended = suspendedAccounts.suspendedUserIds();
		Set<UUID> recipients = listeningFriends.stream().filter(friendId -> !suspended.contains(friendId))
				.collect(Collectors.toSet());
		if (recipients.isEmpty()) {
			return;
		}
		Map<UUID, String> names = profileDirectory.displayNames(Set.of(subjectId));
		Map<UUID, String> avatars = profileDirectory.avatarUrls(Set.of(subjectId));
		FriendNowPlayingResponse payload = new FriendNowPlayingResponse(subjectId, names.get(subjectId),
				avatars.get(subjectId), event.status());
		for (UUID friendId : recipients) {
			hub.push(friendId, EVENT_NAME, payload);
		}
	}
}
