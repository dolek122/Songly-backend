package com.songly.community;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class CommunityNameTakenException extends ApiException {

	CommunityNameTakenException() {
		super(HttpStatus.CONFLICT, "COMMUNITY_NAME_TAKEN", "Społeczność o tej nazwie już istnieje");
	}
}
