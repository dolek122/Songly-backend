package com.songly.community;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class CommunityNotFoundException extends ApiException {

	CommunityNotFoundException() {
		super(HttpStatus.NOT_FOUND, "COMMUNITY_NOT_FOUND", "Społeczność nie została znaleziona");
	}
}
