package com.songly.profile;

import com.songly.identity.UserAccountDeletionRequestedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class ProfileAccountDeletionListener {

	private final ProfileService profileService;

	ProfileAccountDeletionListener(ProfileService profileService) {
		this.profileService = profileService;
	}

	@EventListener
	void onAccountDeletionRequested(UserAccountDeletionRequestedEvent event) {
		profileService.deleteAccountData(event.userId());
	}
}
