package com.songly.nowplaying;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Użytkownik cofnął zgodę w Spotify (albo my straciliśmy refresh token) — link
 * został skasowany i trzeba przejść OAuth od nowa.
 *
 * <p>
 * Osobny kod zamiast {@code PROVIDER_NOT_CONNECTED}, bo dla klienta to inna
 * sytuacja: tam użytkownik nigdy nie podłączył serwisu, tu podłączył i
 * przestało działać — a to druga wiadomość w UI.
 */
class ProviderReauthorizationRequiredException extends ApiException {

	ProviderReauthorizationRequiredException() {
		super(HttpStatus.CONFLICT, "PROVIDER_REAUTHORIZATION_REQUIRED",
				"Utracono dostęp do serwisu muzycznego — podłącz go ponownie");
	}
}
