package com.songly.moderationlog;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class CommunityLogNotFoundException extends ApiException {

	CommunityLogNotFoundException() {
		super(HttpStatus.NOT_FOUND, "COMMUNITY_NOT_FOUND", "Nie ma takiej społeczności");
	}
}
