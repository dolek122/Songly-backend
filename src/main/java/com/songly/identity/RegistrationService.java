package com.songly.identity;

import com.songly.identity.dto.AuthTokensResponse;
import com.songly.identity.dto.RegisterRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@EnableConfigurationProperties(RegistrationProperties.class)
class RegistrationService {

	private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

	private static final String REGISTRATION_ATTEMPTS_METRIC = "songly.auth.registration.attempts";

	private static final String EXISTING_ACCOUNT_WARNING_KEY_PREFIX = "registration:existing-account-warning:";

	private final UserRepository userRepository;
	private final PendingRegistrationRepository pendingRepository;
	private final PasswordEncoder passwordEncoder;
	private final TokenIssuer tokenIssuer;
	private final RegistrationProperties properties;
	private final ApplicationEventPublisher eventPublisher;
	private final MeterRegistry meterRegistry;
	private final StringRedisTemplate redisTemplate;

	RegistrationService(UserRepository userRepository, PendingRegistrationRepository pendingRepository,
			PasswordEncoder passwordEncoder, TokenIssuer tokenIssuer, RegistrationProperties properties,
			ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry, StringRedisTemplate redisTemplate) {
		this.userRepository = userRepository;
		this.pendingRepository = pendingRepository;
		this.passwordEncoder = passwordEncoder;
		this.tokenIssuer = tokenIssuer;
		this.properties = properties;
		this.eventPublisher = eventPublisher;
		this.meterRegistry = meterRegistry;
		this.redisTemplate = redisTemplate;
	}

	@Transactional
	void register(RegisterRequest request) {
		registrationOutcome("requested").increment();
		String passwordHash = passwordEncoder.encode(request.password());

		Optional<User> existing = userRepository.findByEmail(request.email());
		if (existing.isPresent()) {
			User owner = existing.get();
			registrationOutcome("existing-account").increment();
			log.info("Próba rejestracji na adres istniejącego konta {}", owner.getId());
			if (claimExistingAccountWarning(owner.getId())) {
				eventPublisher
						.publishEvent(new RegistrationAttemptedOnExistingAccountEvent(owner.getId(), owner.getEmail()));
			}
			return;
		}

		Instant now = Instant.now();
		Optional<PendingRegistration> pending = pendingRepository.findByEmail(request.email());
		if (pending.isPresent()) {
			PendingRegistration existingPending = pending.get();
			if (existingPending.getCreatedAt().isAfter(now.minus(properties.getResendInterval()))) {
				registrationOutcome("throttled").increment();
				return;
			}
			String reissuedToken = SecureTokens.generate();
			existingPending.reissue(SecureTokens.hash(reissuedToken), now, now.plus(properties.getTtl()));
			registrationOutcome("resent").increment();
			eventPublisher.publishEvent(
					new RegistrationVerificationRequestedEvent(existingPending.getEmail(), reissuedToken));
			return;
		}

		String token = SecureTokens.generate();
		pendingRepository.save(new PendingRegistration(UUID.randomUUID(), request.email(), passwordHash,
				SecureTokens.hash(token), now, now.plus(properties.getTtl())));
		registrationOutcome("pending").increment();
		eventPublisher.publishEvent(new RegistrationVerificationRequestedEvent(request.email(), token));
	}

	private boolean claimExistingAccountWarning(UUID userId) {
		try {
			Boolean claimed = redisTemplate.opsForValue().setIfAbsent(EXISTING_ACCOUNT_WARNING_KEY_PREFIX + userId, "1",
					properties.getResendInterval());
			return claimed == null || claimed;
		} catch (RuntimeException ex) {
			log.warn("Odstęp ostrzeżeń o rejestracji wyłączony dla tej próby — Redis nie odpowiada: {}", ex.toString());
			return true;
		}
	}

	@Transactional
	AuthTokensResponse verify(String token) {
		PendingRegistration pending = pendingRepository.findByTokenHash(SecureTokens.hash(token))
				.orElseThrow(this::invalidToken);
		if (pending.isExpired(Instant.now())) {
			throw invalidToken();
		}
		if (userRepository.existsByEmail(pending.getEmail())) {
			pendingRepository.delete(pending);
			throw invalidToken();
		}

		User user = new User(UUID.randomUUID(), pending.getEmail(), pending.getPasswordHash(), Instant.now());
		userRepository.save(user);
		pendingRepository.delete(pending);

		registrationOutcome("verified").increment();
		log.info("Potwierdzono adres e-mail, założono konto {}", user.getId());
		eventPublisher.publishEvent(new UserRegisteredEvent(user.getId(), Instant.now()));
		return tokenIssuer.issueFor(user);
	}

	private InvalidVerificationTokenException invalidToken() {
		registrationOutcome("invalid-token").increment();
		log.warn("Odrzucony token weryfikacji adresu e-mail");
		return new InvalidVerificationTokenException();
	}

	private Counter registrationOutcome(String outcome) {
		return meterRegistry.counter(REGISTRATION_ATTEMPTS_METRIC, "outcome", outcome);
	}
}
