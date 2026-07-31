package com.songly.community;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class BannedFromCommunityException extends ApiException {

	BannedFromCommunityException() {
		super(HttpStatus.FORBIDDEN, "BANNED_FROM_COMMUNITY", "Masz zakaz uczestnictwa w tej społeczności");
	}
}
