package com.songly.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.songly.identity.dto.AuthTokensResponse;
import com.songly.identity.dto.LoginRequest;
import com.songly.identity.dto.RefreshRequest;
import com.songly.identity.dto.RegisterRequest;
import com.songly.identity.security.JwtService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	@Mock
	private UserRepository userRepository;

	@Mock
	private SessionRepository sessionRepository;

	@Mock
	private PasswordResetTokenRepository passwordResetTokenRepository;

	@Mock
	private JwtService jwtService;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

	/** Prawdziwy rejestr, nie mock — pozwala sprawdzić, ile faktycznie zliczył. */
	private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

	private AuthService authService;

	@BeforeEach
	void setUp() {
		authService = new AuthService(userRepository, sessionRepository, passwordResetTokenRepository, passwordEncoder,
				jwtService, eventPublisher, meterRegistry);
	}

	private double loginAttempts(String outcome) {
		return meterRegistry.counter("songly.auth.login.attempts", "outcome", outcome).count();
	}

	private double refreshAttempts(String outcome) {
		return meterRegistry.counter("songly.auth.refresh.attempts", "outcome", outcome).count();
	}

	@Test
	void register_savesUserAndPublishesEvent_whenEmailNotUsed() {
		when(userRepository.existsByEmail("new@songly.io")).thenReturn(false);
		when(jwtService.generateAccessToken(any(), any())).thenReturn("access-token");
		when(jwtService.getAccessTokenTtl()).thenReturn(Duration.ofMinutes(15));
		when(jwtService.getRefreshTokenTtl()).thenReturn(Duration.ofDays(30));

		AuthTokensResponse response = authService.register(new RegisterRequest("new@songly.io", "password123"));

		assertThat(response.accessToken()).isEqualTo("access-token");
		assertThat(response.refreshToken()).isNotBlank();
		assertThat(response.tokenType()).isEqualTo("Bearer");
		verify(userRepository).save(any(User.class));
		verify(eventPublisher).publishEvent(any(UserRegisteredEvent.class));
		verify(sessionRepository).save(any(Session.class));
	}

	@Test
	void register_throws_whenEmailAlreadyUsed() {
		when(userRepository.existsByEmail("taken@songly.io")).thenReturn(true);

		assertThatThrownBy(() -> authService.register(new RegisterRequest("taken@songly.io", "password123")))
				.isInstanceOf(EmailAlreadyUsedException.class);

		verify(userRepository, never()).save(any());
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void login_returnsTokens_whenCredentialsValid() {
		User user = new User(UUID.randomUUID(), "user@songly.io", passwordEncoder.encode("password123"), Instant.now());
		when(userRepository.findByEmail("user@songly.io")).thenReturn(Optional.of(user));
		when(jwtService.generateAccessToken(any(), any())).thenReturn("access-token");
		when(jwtService.getAccessTokenTtl()).thenReturn(Duration.ofMinutes(15));
		when(jwtService.getRefreshTokenTtl()).thenReturn(Duration.ofDays(30));

		AuthTokensResponse response = authService.login(new LoginRequest("user@songly.io", "password123"));

		assertThat(response.accessToken()).isEqualTo("access-token");
		assertThat(loginAttempts("success")).isEqualTo(1);
	}

	/**
	 * Oba rodzaje niepowodzenia muszą zliczać się jednakowo. Rozdzielenie ich w
	 * metryce podpowiadałoby atakującemu, które adresy istnieją — a do wykrycia
	 * brute-force'u wystarczy sam wzrost liczby porażek.
	 */
	@Test
	void login_throws_whenUserNotFound() {
		when(userRepository.findByEmail("missing@songly.io")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> authService.login(new LoginRequest("missing@songly.io", "password123")))
				.isInstanceOf(InvalidCredentialsException.class);

		assertThat(loginAttempts("failure")).isEqualTo(1);
		assertThat(loginAttempts("success")).isZero();
	}

	@Test
	void login_throws_whenPasswordWrong() {
		User user = new User(UUID.randomUUID(), "user@songly.io", passwordEncoder.encode("password123"), Instant.now());
		when(userRepository.findByEmail("user@songly.io")).thenReturn(Optional.of(user));

		assertThatThrownBy(() -> authService.login(new LoginRequest("user@songly.io", "wrong-password")))
				.isInstanceOf(InvalidCredentialsException.class);

		assertThat(loginAttempts("failure")).isEqualTo(1);
	}

	@Test
	void refresh_rotatesSessionAndIssuesNewTokens_whenSessionActive() {
		UUID userId = UUID.randomUUID();
		Session session = new Session(UUID.randomUUID(), userId, "hash", Instant.now(),
				Instant.now().plusSeconds(3600));
		User user = new User(userId, "user@songly.io", "hash", Instant.now());
		when(sessionRepository.findByRefreshTokenHash(anyString())).thenReturn(Optional.of(session));
		when(userRepository.findById(userId)).thenReturn(Optional.of(user));
		when(jwtService.generateAccessToken(any(), any())).thenReturn("new-access-token");
		when(jwtService.getAccessTokenTtl()).thenReturn(Duration.ofMinutes(15));
		when(jwtService.getRefreshTokenTtl()).thenReturn(Duration.ofDays(30));

		AuthTokensResponse response = authService.refresh(new RefreshRequest("old-refresh-token"));

		assertThat(response.accessToken()).isEqualTo("new-access-token");
		assertThat(session.isRevoked()).isTrue();
		verify(sessionRepository).save(any(Session.class));
	}

	@Test
	void refresh_throws_whenTokenUnknown() {
		when(sessionRepository.findByRefreshTokenHash(anyString())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> authService.refresh(new RefreshRequest("unknown-token")))
				.isInstanceOf(InvalidRefreshTokenException.class);
	}

	/**
	 * Wygaśnięcie to zwykły upływ czasu, nie sygnał kradzieży — nie wolno go
	 * traktować jak nadużycia, bo wylogowywałoby ze wszystkich urządzeń każdego,
	 * kto wrócił do aplikacji po dłuższej przerwie.
	 */
	@Test
	void refresh_throws_whenSessionExpired_withoutTreatingItAsReuse() {
		Session expired = new Session(UUID.randomUUID(), UUID.randomUUID(), "hash", Instant.now().minusSeconds(7200),
				Instant.now().minusSeconds(3600));
		when(sessionRepository.findByRefreshTokenHash(anyString())).thenReturn(Optional.of(expired));

		assertThatThrownBy(() -> authService.refresh(new RefreshRequest("expired-token")))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.isNotInstanceOf(RefreshTokenReuseDetectedException.class);

		verify(sessionRepository, never()).revokeAllForUser(any(), any());
		assertThat(refreshAttempts("expired")).isEqualTo(1);
		assertThat(refreshAttempts("reuse")).isZero();
	}

	/**
	 * Sedno wykrywania: token po rotacji wolno użyć raz, więc drugie użycie znaczy,
	 * że krąży kopia. Nie wiadomo po której stronie, dlatego giną wszystkie sesje.
	 */
	@Test
	void refresh_revokesEverySession_whenRevokedTokenIsReplayed() {
		UUID userId = UUID.randomUUID();
		Session revoked = new Session(UUID.randomUUID(), userId, "hash", Instant.now(),
				Instant.now().plusSeconds(3600));
		revoked.revoke(Instant.now());
		when(sessionRepository.findByRefreshTokenHash(anyString())).thenReturn(Optional.of(revoked));

		assertThatThrownBy(() -> authService.refresh(new RefreshRequest("revoked-token")))
				.isInstanceOf(RefreshTokenReuseDetectedException.class);

		verify(sessionRepository).revokeAllForUser(eq(userId), any(Instant.class));
		assertThat(refreshAttempts("reuse")).isEqualTo(1);
	}

	/**
	 * Odpowiedź musi być nieodróżnialna od zwykłego złego tokenu — inaczej
	 * atakujący dowiadywałby się, że jego kopia została wykryta.
	 */
	@Test
	void reuseDetection_isIndistinguishableFromAnOrdinaryBadToken() {
		Session revoked = new Session(UUID.randomUUID(), UUID.randomUUID(), "hash", Instant.now(),
				Instant.now().plusSeconds(3600));
		revoked.revoke(Instant.now());
		when(sessionRepository.findByRefreshTokenHash(anyString())).thenReturn(Optional.of(revoked));

		RefreshTokenReuseDetectedException reuse = catchThrowableOfType(
				() -> authService.refresh(new RefreshRequest("revoked-token")),
				RefreshTokenReuseDetectedException.class);
		InvalidRefreshTokenException ordinary = new InvalidRefreshTokenException();

		assertThat(reuse.getStatus()).isEqualTo(ordinary.getStatus());
		assertThat(reuse.getCode()).isEqualTo(ordinary.getCode());
		assertThat(reuse.getMessage()).isEqualTo(ordinary.getMessage());
	}

	@Test
	void logout_revokesSession_whenFound() {
		Session session = new Session(UUID.randomUUID(), UUID.randomUUID(), "hash", Instant.now(),
				Instant.now().plusSeconds(3600));
		when(sessionRepository.findByRefreshTokenHash(anyString())).thenReturn(Optional.of(session));

		authService.logout(new RefreshRequest("token"));

		assertThat(session.isRevoked()).isTrue();
	}

	@Test
	void logout_doesNothing_whenSessionNotFound() {
		when(sessionRepository.findByRefreshTokenHash(anyString())).thenReturn(Optional.empty());

		authService.logout(new RefreshRequest("unknown"));
	}

	@Test
	void deleteAccount_publishesEventAndDeletesUser_whenPasswordCorrect() {
		UUID userId = UUID.randomUUID();
		User user = new User(userId, "user@songly.io", passwordEncoder.encode("password123"), Instant.now());
		when(userRepository.findById(userId)).thenReturn(Optional.of(user));

		authService.deleteAccount(userId, "password123");

		verify(eventPublisher).publishEvent(new UserAccountDeletionRequestedEvent(userId));
		verify(sessionRepository).deleteByUserId(userId);
		verify(userRepository).delete(user);
	}

	@Test
	void deleteAccount_throws_whenPasswordWrong() {
		UUID userId = UUID.randomUUID();
		User user = new User(userId, "user@songly.io", passwordEncoder.encode("password123"), Instant.now());
		when(userRepository.findById(userId)).thenReturn(Optional.of(user));

		assertThatThrownBy(() -> authService.deleteAccount(userId, "wrong-password"))
				.isInstanceOf(InvalidCredentialsException.class);

		verify(eventPublisher, never()).publishEvent(any());
		verify(userRepository, never()).delete(any());
	}

	@Test
	void deleteAccount_throws_whenUserNotFound() {
		UUID userId = UUID.randomUUID();
		when(userRepository.findById(userId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> authService.deleteAccount(userId, "password123"))
				.isInstanceOf(InvalidCredentialsException.class);

		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void changePassword_replacesHashAndRevokesEverySession() {
		UUID userId = UUID.randomUUID();
		User user = new User(userId, "user@songly.io", passwordEncoder.encode("password123"), Instant.now());
		when(userRepository.findById(userId)).thenReturn(Optional.of(user));

		authService.changePassword(userId, "password123", "brand-new-password");

		assertThat(passwordEncoder.matches("brand-new-password", user.getPasswordHash())).isTrue();
		assertThat(passwordEncoder.matches("password123", user.getPasswordHash())).isFalse();
		verify(sessionRepository).revokeAllForUser(eq(userId), any(Instant.class));
	}

	/**
	 * Błędne obecne hasło nie może przejść — inaczej przejęty access token
	 * wystarczałby do przejęcia konta na stałe.
	 */
	@Test
	void changePassword_throwsAndChangesNothing_whenCurrentPasswordWrong() {
		UUID userId = UUID.randomUUID();
		String originalHash = passwordEncoder.encode("password123");
		User user = new User(userId, "user@songly.io", originalHash, Instant.now());
		when(userRepository.findById(userId)).thenReturn(Optional.of(user));

		assertThatThrownBy(() -> authService.changePassword(userId, "wrong-password", "brand-new-password"))
				.isInstanceOf(InvalidCredentialsException.class);

		assertThat(user.getPasswordHash()).isEqualTo(originalHash);
		verify(sessionRepository, never()).revokeAllForUser(any(), any());
	}

	/**
	 * Zmiana na to samo hasło nie może przejść po cichu: nie zmieniłaby niczego
	 * poza wylogowaniem użytkownika ze wszystkich urządzeń.
	 */
	@Test
	void changePassword_rejectsNewPasswordIdenticalToCurrent() {
		UUID userId = UUID.randomUUID();
		User user = new User(userId, "user@songly.io", passwordEncoder.encode("password123"), Instant.now());
		when(userRepository.findById(userId)).thenReturn(Optional.of(user));

		assertThatThrownBy(() -> authService.changePassword(userId, "password123", "password123"))
				.isInstanceOf(NewPasswordSameAsCurrentException.class);

		verify(sessionRepository, never()).revokeAllForUser(any(), any());
	}
}
