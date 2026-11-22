package com.songly.identity;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@EnableConfigurationProperties(PasswordResetProperties.class)
class PasswordResetService {

	private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

	private static final String RESET_ATTEMPTS_METRIC = "songly.auth.password-reset.attempts";

	private final UserRepository userRepository;
	private final PasswordResetTokenRepository tokenRepository;
	private final SessionRepository sessionRepository;
	private final PasswordEncoder passwordEncoder;
	private final PasswordResetProperties properties;
	private final ApplicationEventPublisher eventPublisher;
	private final MeterRegistry meterRegistry;

	PasswordResetService(UserRepository userRepository, PasswordResetTokenRepository tokenRepository,
			SessionRepository sessionRepository, PasswordEncoder passwordEncoder, PasswordResetProperties properties,
			ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry) {
		this.userRepository = userRepository;
		this.tokenRepository = tokenRepository;
		this.sessionRepository = sessionRepository;
		this.passwordEncoder = passwordEncoder;
		this.properties = properties;
		this.eventPublisher = eventPublisher;
		this.meterRegistry = meterRegistry;
	}

	@Transactional
	void requestReset(String email) {
		resetOutcome("requested").increment();
		Optional<User> found = userRepository.findByEmail(email);
		if (found.isEmpty()) {
			return;
		}
		User user = found.get();
		Instant now = Instant.now();

		Optional<PasswordResetToken> previous = tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId());
		if (previous.isPresent() && previous.get().getCreatedAt().isAfter(now.minus(properties.getResendInterval()))) {
			resetOutcome("throttled").increment();
			log.info("Wyciszone żądanie resetu hasła (odstęp {}), konto: {}", properties.getResendInterval(),
					user.getId());
			return;
		}
		tokenRepository.deleteByUserId(user.getId());

		String token = SecureTokens.generate();
		tokenRepository.save(new PasswordResetToken(UUID.randomUUID(), user.getId(), SecureTokens.hash(token), now,
				now.plus(properties.getTtl())));
		log.info("Wydano token resetu hasła dla konta {}, ważny {}", user.getId(), properties.getTtl());
		eventPublisher.publishEvent(new PasswordResetRequestedEvent(user.getId(), user.getEmail(), token));
	}

	@Transactional
	void resetPassword(String token, String newPassword) {
		PasswordResetToken resetToken = tokenRepository.findByTokenHash(SecureTokens.hash(token))
				.orElseThrow(this::invalidToken);
		if (resetToken.isExpired(Instant.now())) {
			throw invalidToken();
		}
		UUID userId = resetToken.getUserId();
		User user = userRepository.findById(userId).orElseThrow(this::invalidToken);

		user.changePassword(passwordEncoder.encode(newPassword));
		int revoked = sessionRepository.revokeAllForUser(userId, Instant.now());
		eventPublisher.publishEvent(new UserSessionsRevokedEvent(userId));
		tokenRepository.deleteByUserId(userId);

		resetOutcome("success").increment();
		log.info("Reset hasła konta {}, odwołane sesje: {}", userId, revoked);
	}

	private InvalidPasswordResetTokenException invalidToken() {
		resetOutcome("invalid-token").increment();
		log.warn("Odrzucony token resetu hasła");
		return new InvalidPasswordResetTokenException();
	}

	private Counter resetOutcome(String outcome) {
		return meterRegistry.counter(RESET_ATTEMPTS_METRIC, "outcome", outcome);
	}
}
