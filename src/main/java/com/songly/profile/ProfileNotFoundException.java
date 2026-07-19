package com.songly.profile;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class ProfileNotFoundException extends ApiException {

	ProfileNotFoundException() {
		super(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Profil nie został znaleziony");
	}
}
