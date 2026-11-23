package com.songly.content;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class PostInCommunityException extends ApiException {

	PostInCommunityException() {
		super(HttpStatus.CONFLICT, "POST_IN_COMMUNITY", "Post ze społeczności usuwa moderacja tej społeczności");
	}
}
