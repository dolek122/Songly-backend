package com.songly.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.songly.identity.dto.AuthTokensResponse;
import com.songly.identity.dto.LoginRequest;
import com.songly.identity.dto.RefreshRequest;
import com.songly.identity.dto.RegisterRequest;
import com.songly.identity.security.JwtService;
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
	private JwtService jwtService;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

	private AuthService authService;

	@BeforeEach
	void setUp() {
		authService = new AuthService(userRepository, sessionRepository, passwordEncoder, jwtService, eventPublisher);
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
	}

	@Test
	void login_throws_whenUserNotFound() {
		when(userRepository.findByEmail("missing@songly.io")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> authService.login(new LoginRequest("missing@songly.io", "password123")))
				.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void login_throws_whenPasswordWrong() {
		User user = new User(UUID.randomUUID(), "user@songly.io", passwordEncoder.encode("password123"), Instant.now());
		when(userRepository.findByEmail("user@songly.io")).thenReturn(Optional.of(user));

		assertThatThrownBy(() -> authService.login(new LoginRequest("user@songly.io", "wrong-password")))
				.isInstanceOf(InvalidCredentialsException.class);
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

	@Test
	void refresh_throws_whenSessionExpired() {
		Session expired = new Session(UUID.randomUUID(), UUID.randomUUID(), "hash", Instant.now().minusSeconds(7200),
				Instant.now().minusSeconds(3600));
		when(sessionRepository.findByRefreshTokenHash(anyString())).thenReturn(Optional.of(expired));

		assertThatThrownBy(() -> authService.refresh(new RefreshRequest("expired-token")))
				.isInstanceOf(InvalidRefreshTokenException.class);
	}

	@Test
	void refresh_throws_whenSessionAlreadyRevoked() {
		Session revoked = new Session(UUID.randomUUID(), UUID.randomUUID(), "hash", Instant.now(),
				Instant.now().plusSeconds(3600));
		revoked.revoke();
		when(sessionRepository.findByRefreshTokenHash(anyString())).thenReturn(Optional.of(revoked));

		assertThatThrownBy(() -> authService.refresh(new RefreshRequest("revoked-token")))
				.isInstanceOf(InvalidRefreshTokenException.class);
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
}
