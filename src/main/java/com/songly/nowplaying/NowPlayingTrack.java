package com.songly.nowplaying;

/** Znormalizowany "co teraz gra" niezależny od providera. */
record NowPlayingTrack(String title, String artist, String album, String imageUrl, boolean playing) {
}
