package com.songly.nowplaying;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class ProviderNotConnectedException extends ApiException {

	ProviderNotConnectedException() {
		super(HttpStatus.NOT_FOUND, "PROVIDER_NOT_CONNECTED", "Brak połączonego serwisu muzycznego");
	}
}
