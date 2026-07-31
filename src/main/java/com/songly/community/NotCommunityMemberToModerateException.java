package com.songly.community;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class NotCommunityMemberToModerateException extends ApiException {

	NotCommunityMemberToModerateException() {
		super(HttpStatus.NOT_FOUND, "NOT_COMMUNITY_MEMBER", "Ten użytkownik nie należy do społeczności");
	}
}
