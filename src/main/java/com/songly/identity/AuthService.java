package com.songly.identity;

import com.songly.identity.dto.AuthTokensResponse;
import com.songly.identity.dto.LoginRequest;
import com.songly.identity.dto.RefreshRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	private static final String LOGIN_ATTEMPTS_METRIC = "songly.auth.login.attempts";

	private static final String REFRESH_ATTEMPTS_METRIC = "songly.auth.refresh.attempts";

	private final UserRepository userRepository;
	private final SessionRepository sessionRepository;
	private final PasswordResetTokenRepository passwordResetTokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final TokenIssuer tokenIssuer;
	private final ApplicationEventPublisher eventPublisher;
	private final MeterRegistry meterRegistry;
	private final AccountEraser accountEraser;
	private final LoginAttemptLimiter loginAttemptLimiter;

	private final String unknownAccountHash;

	AuthService(UserRepository userRepository, SessionRepository sessionRepository,
			PasswordResetTokenRepository passwordResetTokenRepository, PasswordEncoder passwordEncoder,
			TokenIssuer tokenIssuer, ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry,
			AccountEraser accountEraser, LoginAttemptLimiter loginAttemptLimiter) {
		this.userRepository = userRepository;
		this.sessionRepository = sessionRepository;
		this.passwordResetTokenRepository = passwordResetTokenRepository;
		this.passwordEncoder = passwordEncoder;
		this.tokenIssuer = tokenIssuer;
		this.eventPublisher = eventPublisher;
		this.meterRegistry = meterRegistry;
		this.accountEraser = accountEraser;
		this.loginAttemptLimiter = loginAttemptLimiter;
		this.unknownAccountHash = passwordEncoder.encode(SecureTokens.generate());
	}

	@Transactional
	AuthTokensResponse login(LoginRequest request) {
		try {
			loginAttemptLimiter.recordAttempt(request.email());
		} catch (LoginRateLimitExceededException ex) {
			loginOutcome("throttled").increment();
			log.warn("Limit prób logowania na adres przekroczony");
			throw ex;
		}
		User user = userRepository.findByEmail(request.email()).orElse(null);
		if (user == null) {
			passwordEncoder.matches(request.password(), unknownAccountHash);
			auditFailedLogin("nieznane konto", null);
			throw new InvalidCredentialsException();
		}
		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			auditFailedLogin("błędne hasło", user.getId());
			throw new InvalidCredentialsException();
		}
		loginAttemptLimiter.reset(request.email());
		loginOutcome("success").increment();
		log.info("Zalogowano użytkownika {}", user.getId());
		return tokenIssuer.issueFor(user);
	}

	private void auditFailedLogin(String reason, UUID userId) {
		loginOutcome("failure").increment();
		log.warn("Nieudane logowanie ({}), konto: {}", reason, userId);
	}

	private Counter loginOutcome(String outcome) {
		return meterRegistry.counter(LOGIN_ATTEMPTS_METRIC, "outcome", outcome);
	}

	@Transactional(noRollbackFor = RefreshTokenReuseDetectedException.class)
	AuthTokensResponse refresh(RefreshRequest request) {
		String hash = SecureTokens.hash(request.refreshToken());
		Session session = sessionRepository.findByRefreshTokenHashForUpdate(hash).orElseGet(() -> {
			refreshOutcome("invalid").increment();
			throw new InvalidRefreshTokenException();
		});
		Instant now = Instant.now();
		if (session.isRevoked()) {
			refreshOutcome("reuse").increment();
			int revoked = sessionRepository.revokeAllForUser(session.getUserId(), now);
			eventPublisher.publishEvent(new UserSessionsRevokedEvent(session.getUserId()));
			log.warn("Ponowne użycie refresh tokenu, konto: {} — odwołane sesje: {}", session.getUserId(), revoked);
			throw new RefreshTokenReuseDetectedException();
		}
		if (!session.isActive(now)) {
			refreshOutcome("expired").increment();
			throw new InvalidRefreshTokenException();
		}
		session.revoke(now);
		User user = userRepository.findById(session.getUserId()).orElseThrow(InvalidRefreshTokenException::new);
		refreshOutcome("success").increment();
		return tokenIssuer.issueFor(user);
	}

	private Counter refreshOutcome(String outcome) {
		return meterRegistry.counter(REFRESH_ATTEMPTS_METRIC, "outcome", outcome);
	}

	@Transactional
	void logout(RefreshRequest request) {
		sessionRepository.findByRefreshTokenHash(SecureTokens.hash(request.refreshToken()))
				.ifPresent(session -> session.revoke(Instant.now()));
	}

	@Transactional
	void changePassword(UUID userId, String currentPassword, String newPassword) {
		User user = userRepository.findById(userId).orElseThrow(InvalidCredentialsException::new);
		if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			log.warn("Nieudana zmiana hasła (błędne obecne hasło), konto: {}", userId);
			throw new InvalidCredentialsException();
		}
		if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
			throw new NewPasswordSameAsCurrentException();
		}
		user.changePassword(passwordEncoder.encode(newPassword));
		int revoked = sessionRepository.revokeAllForUser(userId, Instant.now());
		eventPublisher.publishEvent(new UserSessionsRevokedEvent(userId));
		passwordResetTokenRepository.deleteByUserId(userId);
		log.info("Zmiana hasła konta {}, odwołane sesje: {}", userId, revoked);
	}

	@Transactional
	void deleteAccount(UUID userId, String password) {
		User user = userRepository.findById(userId).orElseThrow(InvalidCredentialsException::new);
		if (!passwordEncoder.matches(password, user.getPasswordHash())) {
			throw new InvalidCredentialsException();
		}
		log.warn("Usuwanie konta {} na żądanie użytkownika (RODO)", userId);
		accountEraser.erase(user);
	}

}
