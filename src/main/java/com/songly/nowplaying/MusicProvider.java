package com.songly.nowplaying;

import java.util.Optional;
import java.util.UUID;

interface MusicProvider {

	String name();

	String buildAuthorizeUrl(String state);

	void connect(UUID userId, String authorizationCode);

	Optional<NowPlayingTrack> getCurrentlyPlaying(UUID userId);
}
