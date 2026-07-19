package com.songly.nowplaying.dto;

import java.time.Instant;

public record NowPlayingStatusResponse(String provider, String title, String artist, String album, String imageUrl,
		boolean playing, Instant fetchedAt) {
}
