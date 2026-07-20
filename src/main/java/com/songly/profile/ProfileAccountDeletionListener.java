package com.songly.profile;

import com.songly.identity.UserAccountDeletionRequestedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Zwykły {@code @EventListener} (nie {@code @TransactionalEventListener
 * AFTER_COMMIT}) celowo — musi posprzątać dane PRZED usunięciem wiersza
 * {@code users} w tej samej transakcji, patrz
 * {@link UserAccountDeletionRequestedEvent}.
 */
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
