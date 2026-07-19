package com.songly.content;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class PostNotFoundException extends ApiException {

	PostNotFoundException() {
		super(HttpStatus.NOT_FOUND, "POST_NOT_FOUND", "Post nie został znaleziony");
	}
}
