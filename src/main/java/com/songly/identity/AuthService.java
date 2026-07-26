package com.songly.identity;

import com.songly.identity.dto.AuthTokensResponse;
import com.songly.identity.dto.LoginRequest;
import com.songly.identity.dto.RefreshRequest;
import com.songly.identity.dto.RegisterRequest;
import com.songly.identity.security.JwtService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuthService {

	private final UserRepository userRepository;
	private final SessionRepository sessionRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final ApplicationEventPublisher eventPublisher;

	AuthService(UserRepository userRepository, SessionRepository sessionRepository, PasswordEncoder passwordEncoder,
			JwtService jwtService, ApplicationEventPublisher eventPublisher) {
		this.userRepository = userRepository;
		this.sessionRepository = sessionRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.eventPublisher = eventPublisher;
	}

	@Transactional
	AuthTokensResponse register(RegisterRequest request) {
		if (userRepository.existsByEmail(request.email())) {
			throw new EmailAlreadyUsedException(request.email());
		}
		User user = new User(UUID.randomUUID(), request.email(), passwordEncoder.encode(request.password()),
				Instant.now());
		userRepository.save(user);
		eventPublisher.publishEvent(new UserRegisteredEvent(user.getId(), user.getEmail(), Instant.now()));
		return issueTokens(user);
	}

	@Transactional
	AuthTokensResponse login(LoginRequest request) {
		User user = userRepository.findByEmail(request.email()).orElseThrow(InvalidCredentialsException::new);
		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw new InvalidCredentialsException();
		}
		return issueTokens(user);
	}

	@Transactional
	AuthTokensResponse refresh(RefreshRequest request) {
		String hash = hashToken(request.refreshToken());
		Session session = sessionRepository.findByRefreshTokenHash(hash).orElseThrow(InvalidRefreshTokenException::new);
		Instant now = Instant.now();
		if (!session.isActive(now)) {
			throw new InvalidRefreshTokenException();
		}
		session.revoke(now);
		User user = userRepository.findById(session.getUserId()).orElseThrow(InvalidRefreshTokenException::new);
		return issueTokens(user);
	}

	@Transactional
	void logout(RefreshRequest request) {
		sessionRepository.findByRefreshTokenHash(hashToken(request.refreshToken()))
				.ifPresent(session -> session.revoke(Instant.now()));
	}

	/**
	 * Wymaga ponownego podania hasła — sam ważny access token nie wystarcza na
	 * akcję nieodwracalną (Claude.md §9, RODO — prawo do usunięcia konta). Event
	 * leci PRZED usunięciem wiersza {@code users}: pozostałe moduły mają FK do
	 * {@code users} bez CASCADE, więc muszą posprzątać swoje dane w tej samej
	 * transakcji, zanim to konto w ogóle da się usunąć.
	 */
	@Transactional
	void deleteAccount(UUID userId, String password) {
		User user = userRepository.findById(userId).orElseThrow(InvalidCredentialsException::new);
		if (!passwordEncoder.matches(password, user.getPasswordHash())) {
			throw new InvalidCredentialsException();
		}
		eventPublisher.publishEvent(new UserAccountDeletionRequestedEvent(userId));
		sessionRepository.deleteByUserId(userId);
		userRepository.delete(user);
	}

	private AuthTokensResponse issueTokens(User user) {
		String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
		String refreshToken = generateRefreshToken();
		Instant now = Instant.now();
		Session session = new Session(UUID.randomUUID(), user.getId(), hashToken(refreshToken), now,
				now.plus(jwtService.getRefreshTokenTtl()));
		sessionRepository.save(session);
		return new AuthTokensResponse(accessToken, refreshToken, "Bearer", jwtService.getAccessTokenTtl().toSeconds());
	}

	private String generateRefreshToken() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String hashToken(String token) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return Base64.getEncoder().encodeToString(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
