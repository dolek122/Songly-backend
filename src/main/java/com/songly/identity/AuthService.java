package com.songly.identity;

import com.songly.identity.dto.AuthTokensResponse;
import com.songly.identity.dto.LoginRequest;
import com.songly.identity.dto.RefreshRequest;
import com.songly.identity.dto.RegisterRequest;
import com.songly.identity.security.JwtService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
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

	/**
	 * W Prometheusie: {@code songly_auth_login_attempts_total{outcome="..."}} —
	 * nagły skok {@code failure} to sygnał brute-force'u, widoczny bez parsowania
	 * logów.
	 */
	private static final String LOGIN_ATTEMPTS_METRIC = "songly.auth.login.attempts";

	private final UserRepository userRepository;
	private final SessionRepository sessionRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final ApplicationEventPublisher eventPublisher;
	private final MeterRegistry meterRegistry;

	AuthService(UserRepository userRepository, SessionRepository sessionRepository, PasswordEncoder passwordEncoder,
			JwtService jwtService, ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry) {
		this.userRepository = userRepository;
		this.sessionRepository = sessionRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.eventPublisher = eventPublisher;
		this.meterRegistry = meterRegistry;
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

	/**
	 * Wynik każdej próby logowania trafia do logu i do licznika Micrometera.
	 * Wcześniej brute-force na konkretne konto nie zostawiał żadnego śladu — rate
	 * limiter go hamował, ale nikt nie mógł zauważyć, że w ogóle miał miejsce.
	 *
	 * <p>
	 * Świadomie <b>bez adresu e-mail</b> w logach: identyfikujemy po
	 * {@code userId}, a przy nieistniejącym koncie nie logujemy niczego, co by je
	 * opisywało. Logi krążą po systemach, do których RODO stosuje się tak samo jak
	 * do bazy (Claude.md §9), a do zbadania ataku wystarczy licznik i identyfikator
	 * żądania.
	 */
	@Transactional
	AuthTokensResponse login(LoginRequest request) {
		User user = userRepository.findByEmail(request.email()).orElse(null);
		if (user == null) {
			auditFailedLogin("nieznane konto", null);
			throw new InvalidCredentialsException();
		}
		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			auditFailedLogin("błędne hasło", user.getId());
			throw new InvalidCredentialsException();
		}
		loginOutcome("success").increment();
		log.info("Zalogowano użytkownika {}", user.getId());
		return issueTokens(user);
	}

	private void auditFailedLogin(String reason, UUID userId) {
		loginOutcome("failure").increment();
		log.warn("Nieudane logowanie ({}), konto: {}", reason, userId);
	}

	private Counter loginOutcome(String outcome) {
		return meterRegistry.counter(LOGIN_ATTEMPTS_METRIC, "outcome", outcome);
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
	 * Zmiana hasła. Wymaga podania obecnego hasła, tak samo jak usunięcie konta:
	 * sam ważny access token nie wystarcza, bo mógł zostać przejęty — a to jest
	 * dokładnie ta operacja, którą przejmujący chciałby wykonać.
	 *
	 * <p>
	 * <b>Odwołujemy wszystkie sesje, łącznie z bieżącą.</b> Zmiana hasła po
	 * kradzieży, która zostawia złodziejowi ważny refresh token, nie jest zmianą
	 * hasła — jest złudzeniem bezpieczeństwa. Bieżącej sesji nie da się zresztą
	 * oszczędzić: access token nie niesie identyfikatora sesji, więc nie wiemy,
	 * która z nich należy do pytającego. Użytkownik loguje się ponownie wszędzie,
	 * co jest zachowaniem oczekiwanym po zmianie hasła.
	 *
	 * <p>
	 * Czego to <b>nie</b> robi: wydane access tokeny żyją dalej aż do wygaśnięcia
	 * (domyślnie 15 minut), bo JWT jest bezstanowy z założenia (§6). Zamknięcie
	 * tego okna wymagałoby sprawdzania sesji przy każdym żądaniu, czyli oddania
	 * właśnie tej bezstanowości. Rewokacja odcina odnawianie, a nie trwającą minutę
	 * resztkę ważności.
	 */
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
		log.info("Zmiana hasła konta {}, odwołane sesje: {}", userId, revoked);
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
		// Nieodwracalna operacja obejmująca wszystkie moduły — jeśli kiedyś trzeba
		// będzie odpowiedzieć na pytanie "co się stało z tym kontem", to jest jedyny
		// ślad, jaki po niej zostaje.
		log.warn("Usuwanie konta {} na żądanie użytkownika (RODO)", userId);
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
