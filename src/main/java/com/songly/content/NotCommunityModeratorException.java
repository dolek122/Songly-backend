package com.songly.content;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class NotCommunityModeratorException extends ApiException {

	NotCommunityModeratorException() {
		super(HttpStatus.FORBIDDEN, "NOT_COMMUNITY_MODERATOR", "Tylko moderator społeczności może wykonać tę akcję");
	}
}
