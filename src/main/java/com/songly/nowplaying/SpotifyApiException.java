package com.songly.nowplaying;

class SpotifyApiException extends RuntimeException {

	SpotifyApiException(int statusCode) {
		super("Spotify API zwróciło status " + statusCode);
	}
}
