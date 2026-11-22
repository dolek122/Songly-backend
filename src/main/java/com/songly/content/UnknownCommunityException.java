package com.songly.content;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class UnknownCommunityException extends ApiException {

	UnknownCommunityException() {
		super(HttpStatus.NOT_FOUND, "COMMUNITY_NOT_FOUND", "Społeczność nie została znaleziona");
	}
}
