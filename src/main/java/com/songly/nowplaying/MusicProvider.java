package com.songly.nowplaying;

import java.util.Optional;
import java.util.UUID;

/**
 * Granica na wielu dostawców muzyki (Claude.md §7): dodanie kolejnego serwisu
 * (Apple Music, Tidal...) to nowa implementacja, bez ruszania reszty modułu.
 */
interface MusicProvider {

	String name();

	String buildAuthorizeUrl(String state);

	void connect(UUID userId, String authorizationCode);

	Optional<NowPlayingTrack> getCurrentlyPlaying(UUID userId);
}
