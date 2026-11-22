package com.songly.nowplaying;

import com.songly.common.web.ApiException;
import java.time.Duration;
import org.springframework.http.HttpStatus;

class SpotifyApiException extends ApiException {

	private static final String INVALID_GRANT = "invalid_grant";

	private final int upstreamStatus;
	private final String error;
	private final Duration retryAfter;

	SpotifyApiException(int upstreamStatus, String error, Duration retryAfter) {
		super(HttpStatus.BAD_GATEWAY, "MUSIC_PROVIDER_ERROR", "Serwis muzyczny odpowiedział błędem");
		this.upstreamStatus = upstreamStatus;
		this.error = error;
		this.retryAfter = retryAfter;
	}

	int upstreamStatus() {
		return upstreamStatus;
	}

	boolean isInvalidGrant() {
		return INVALID_GRANT.equals(error);
	}

	boolean isRateLimited() {
		return upstreamStatus == HttpStatus.TOO_MANY_REQUESTS.value();
	}

	Duration retryAfter() {
		return retryAfter;
	}

	@Override
	public String toString() {
		return "SpotifyApiException[status=" + upstreamStatus + ", error=" + error + "]";
	}
}
