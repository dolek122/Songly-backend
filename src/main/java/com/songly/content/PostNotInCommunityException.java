package com.songly.content;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class PostNotInCommunityException extends ApiException {

	PostNotInCommunityException() {
		super(HttpStatus.BAD_REQUEST, "POST_NOT_IN_COMMUNITY",
				"Zgłaszać można wyłącznie posty opublikowane w społeczności");
	}
}
