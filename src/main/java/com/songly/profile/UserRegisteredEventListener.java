package com.songly.profile;

import com.songly.identity.UserRegisteredEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reakcja na rejestrację w module identity: zakłada domyślny profil (User 1—1
 * Profile) bez sięgania po encję {@code User} spoza modułu identity — jedyny
 * kontakt to zdarzenie Springa.
 */
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
