package com.songly.nowplaying;

import com.songly.common.web.ApiException;
import java.time.Duration;
import org.springframework.http.HttpStatus;

/**
 * Spotify odpowiedziało błędem. Świadomie {@link ApiException} z 502, a nie
 * goły {@code RuntimeException}: bez tego każda czkawka po stronie providera
 * wpadała do łapacza {@code Exception} w {@code GlobalExceptionHandler} i
 * wracała do klienta jako 500 {@code INTERNAL_ERROR} ze stack tracem w logach —
 * czyli cudza awaria wyglądała w monitoringu jak nasza.
 *
 * <p>
 * Na zewnątrz jest jeden kod błędu, bo klient i tak reaguje tak samo ("spróbuj
 * później"). Rozróżnienia niesione przez ten wyjątek są dla nas: {@code
 * invalid_grant} przerabia {@link SpotifyProvider} na sensowną odpowiedź, a
 * {@link #retryAfter()} czyta poller, żeby przestać dobijać się do API, które
 * właśnie powiedziało "dość" (Claude.md §7).
 */
class SpotifyApiException extends ApiException {

	/** Spotify sygnalizuje tym kodem odwołaną zgodę albo zużyty kod autoryzacji. */
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

	/** {@code null}, gdy Spotify nie podało nagłówka {@code Retry-After}. */
	Duration retryAfter() {
		return retryAfter;
	}

	/**
	 * Logi polla identyfikują użytkownika osobno, więc tutaj liczy się tylko to,
	 * <i>co</i> odpowiedział provider — stąd status i kod błędu w treści, a nie
	 * samo "błąd providera".
	 */
	@Override
	public String toString() {
		return "SpotifyApiException[status=" + upstreamStatus + ", error=" + error + "]";
	}
}
