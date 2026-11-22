package com.songly.nowplaying;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class ProviderReauthorizationRequiredException extends ApiException {

	ProviderReauthorizationRequiredException() {
		super(HttpStatus.CONFLICT, "PROVIDER_REAUTHORIZATION_REQUIRED",
				"Utracono dostęp do serwisu muzycznego — podłącz go ponownie");
	}
}
