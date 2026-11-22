package com.songly.content;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class NotCommunityMemberException extends ApiException {

	NotCommunityMemberException() {
		super(HttpStatus.FORBIDDEN, "NOT_COMMUNITY_MEMBER", "Tylko członek społeczności może w niej publikować");
	}
}
