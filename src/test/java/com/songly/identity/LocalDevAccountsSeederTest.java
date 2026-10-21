package com.songly.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit, nie integracja: testy integracyjne dzielą jedną bazę między
 * kontekstami, więc zasiane konto admina zostałoby w niej dla wszystkich
 * pozostałych testów (np. jako adresat maili do administracji).
 */
@ExtendWith(MockitoExtension.class)
class LocalDevAccountsSeederTest {

	@Mock
	private UserRepository userRepository;

	@Mock
	private PasswordEncoder passwordEncoder;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private LocalDevAccountsSeeder seeder;

	@BeforeEach
	void setUp() {
		seeder = new LocalDevAccountsSeeder(userRepository, passwordEncoder, eventPublisher, false);
	}

	@Test
	void seedsAdminAndRegularUserThroughRegistrationEvent() {
		when(passwordEncoder.encode(anyString())).thenReturn("hash");

		seeder.run(null);

		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(userRepository, times(2)).save(saved.capture());
		Map<String, User> byEmail = saved.getAllValues().stream()
				.collect(Collectors.toMap(User::getEmail, Function.identity()));
		assertThat(byEmail.get(LocalDevAccountsSeeder.ADMIN_EMAIL).isAdmin()).isTrue();
		assertThat(byEmail.get(LocalDevAccountsSeeder.USER_EMAIL).isAdmin()).isFalse();

		// Bez zdarzenia konto nie miałoby profilu, czyli 404 na /profiles/me.
		ArgumentCaptor<UserRegisteredEvent> events = ArgumentCaptor.forClass(UserRegisteredEvent.class);
		verify(eventPublisher, times(2)).publishEvent(events.capture());
		assertThat(events.getAllValues()).extracting(UserRegisteredEvent::email).containsExactlyInAnyOrderElementsOf(
				List.of(LocalDevAccountsSeeder.ADMIN_EMAIL, LocalDevAccountsSeeder.USER_EMAIL));
	}

	/** Restart nie może resetować haseł ani tego, co ktoś zdążył przeklikać. */
	@Test
	void leavesExistingAccountsUntouched() {
		when(userRepository.existsByEmail(anyString())).thenReturn(true);

		seeder.run(null);

		verify(userRepository, never()).save(any());
		verify(eventPublisher, never()).publishEvent(any());
	}

	/**
	 * Hasła leżą w repo — poza profilem local to byłby publiczny klucz do
	 * administracji.
	 */
	@Test
	void isActiveOnlyInLocalProfile() {
		Profile profile = LocalDevAccountsSeeder.class.getAnnotation(Profile.class);

		assertThat(profile).isNotNull();
		assertThat(profile.value()).containsExactly("local");
	}

	/**
	 * Druga zapora obok {@code @Profile}: profil local włączony przez pomyłkę na
	 * środowisku z prawdziwymi sekretami nie może założyć admina ze znanym hasłem.
	 */
	@Test
	void refusesToStart_whereProductionSecretsAreRequired() {
		assertThatThrownBy(() -> new LocalDevAccountsSeeder(userRepository, passwordEncoder, eventPublisher, true))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("Wyłącz profil local");
	}
}
