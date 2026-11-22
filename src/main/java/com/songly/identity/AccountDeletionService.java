package com.songly.identity;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@EnableConfigurationProperties(AccountDeletionProperties.class)
class AccountDeletionService {

	private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

	private static final String ATTEMPTS_METRIC = "songly.auth.account-deletion.attempts";

	private final UserRepository userRepository;
	private final AccountDeletionTokenRepository tokenRepository;
	private final AccountEraser accountEraser;
	private final AccountDeletionProperties properties;
	private final ApplicationEventPublisher eventPublisher;
	private final MeterRegistry meterRegistry;

	AccountDeletionService(UserRepository userRepository, AccountDeletionTokenRepository tokenRepository,
			AccountEraser accountEraser, AccountDeletionProperties properties, ApplicationEventPublisher eventPublisher,
			MeterRegistry meterRegistry) {
		this.userRepository = userRepository;
		this.tokenRepository = tokenRepository;
		this.accountEraser = accountEraser;
		this.properties = properties;
		this.eventPublisher = eventPublisher;
		this.meterRegistry = meterRegistry;
	}

	@Transactional
	void requestDeletion(String email) {
		outcome("requested");
		Optional<User> found = userRepository.findByEmail(email);
		if (found.isEmpty()) {
			return;
		}
		User user = found.get();
		Instant now = Instant.now();
		Optional<AccountDeletionToken> previous = tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId());
		if (previous.isPresent() && previous.get().getCreatedAt().isAfter(now.minus(properties.getResendInterval()))) {
			outcome("throttled");
			return;
		}
		tokenRepository.deleteByUserId(user.getId());
		String token = SecureTokens.generate();
		tokenRepository.save(new AccountDeletionToken(UUID.randomUUID(), user.getId(), SecureTokens.hash(token), now,
				now.plus(properties.getTtl())));
		log.info("Wydano link usunięcia konta {}, ważny {}", user.getId(), properties.getTtl());
		eventPublisher.publishEvent(new AccountDeletionLinkRequestedEvent(user.getId(), user.getEmail(), token));
	}

	@Transactional(readOnly = true)
	boolean isValid(String token) {
		return validToken(token).isPresent();
	}

	@Transactional
	boolean confirmDeletion(String token) {
		Optional<AccountDeletionToken> deletionToken = validToken(token);
		if (deletionToken.isEmpty()) {
			outcome("invalid-token");
			return false;
		}
		UUID userId = deletionToken.get().getUserId();
		Optional<User> user = userRepository.findById(userId);
		if (user.isEmpty()) {
			outcome("invalid-token");
			return false;
		}
		log.warn("Usuwanie konta {} potwierdzone linkiem z maila (RODO)", userId);
		accountEraser.erase(user.get());
		outcome("deleted");
		return true;
	}

	private Optional<AccountDeletionToken> validToken(String token) {
		if (token == null || token.isBlank()) {
			return Optional.empty();
		}
		Instant now = Instant.now();
		return tokenRepository.findByTokenHash(SecureTokens.hash(token)).filter(found -> !found.isExpired(now));
	}

	private void outcome(String outcome) {
		meterRegistry.counter(ATTEMPTS_METRIC, "outcome", outcome).increment();
	}
}
