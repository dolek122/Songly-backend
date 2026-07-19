package com.songly.community;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class NotCommunityOwnerException extends ApiException {

	NotCommunityOwnerException() {
		super(HttpStatus.FORBIDDEN, "NOT_COMMUNITY_OWNER", "Tylko właściciel społeczności może wykonać tę akcję");
	}
}
