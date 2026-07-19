package com.songly.social;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class SelfActionNotAllowedException extends ApiException {

	SelfActionNotAllowedException() {
		super(HttpStatus.BAD_REQUEST, "SELF_ACTION_NOT_ALLOWED", "Nie możesz wykonać tej akcji na własnym koncie");
	}
}
