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
		if (!hub.hasAnySubscriber()) {
			return;
		}
		UUID subjectId = event.userId();
		if (event.status() != null && !nowPlayingService.sharesStatus(subjectId)) {
			return;
		}
		Set<UUID> listeningFriends = socialGraph.friendIds(subjectId).stream().filter(hub::hasSubscribers)
				.collect(Collectors.toSet());
		if (listeningFriends.isEmpty()) {
			return;
		}
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
