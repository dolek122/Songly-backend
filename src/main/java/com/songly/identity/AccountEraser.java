package com.songly.identity;

import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class AccountEraser {

	private final UserRepository userRepository;
	private final SessionRepository sessionRepository;
	private final PasswordResetTokenRepository passwordResetTokenRepository;
	private final AccountDeletionTokenRepository accountDeletionTokenRepository;
	private final ApplicationEventPublisher eventPublisher;

	AccountEraser(UserRepository userRepository, SessionRepository sessionRepository,
			PasswordResetTokenRepository passwordResetTokenRepository,
			AccountDeletionTokenRepository accountDeletionTokenRepository, ApplicationEventPublisher eventPublisher) {
		this.userRepository = userRepository;
		this.sessionRepository = sessionRepository;
		this.passwordResetTokenRepository = passwordResetTokenRepository;
		this.accountDeletionTokenRepository = accountDeletionTokenRepository;
		this.eventPublisher = eventPublisher;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	void erase(User user) {
		UUID userId = user.getId();
		eventPublisher.publishEvent(new UserAccountDeletionRequestedEvent(userId));
		sessionRepository.deleteByUserId(userId);
		passwordResetTokenRepository.deleteByUserId(userId);
		accountDeletionTokenRepository.deleteByUserId(userId);
		userRepository.delete(user);
	}
}
