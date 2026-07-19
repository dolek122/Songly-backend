package com.songly.content;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class CommentNotFoundException extends ApiException {

	CommentNotFoundException() {
		super(HttpStatus.NOT_FOUND, "COMMENT_NOT_FOUND", "Komentarz nie został znaleziony");
	}
}
