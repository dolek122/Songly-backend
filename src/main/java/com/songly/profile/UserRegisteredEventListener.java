package com.songly.profile;

import com.songly.identity.UserRegisteredEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class UserRegisteredEventListener {

	private final ProfileService profileService;

	UserRegisteredEventListener(ProfileService profileService) {
		this.profileService = profileService;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onUserRegistered(UserRegisteredEvent event) {
		profileService.createDefaultProfile(event.userId());
	}
}
