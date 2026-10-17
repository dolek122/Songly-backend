package com.songly.identity;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dwa gotowe konta do ręcznego przeklikania aplikacji lokalnie: administrator i
 * zwykły użytkownik.
 *
 * <p>
 * <b>Wyłącznie profil {@code local}</b> — hasła są w repo, więc to samo konto
 * na jakimkolwiek wspólnym środowisku byłoby publicznie znanym kluczem do
 * administracji. Profil nie włącza się sam: trzeba go podać przy starcie.
 *
 * <p>
 * Konta powstają tą samą drogą co po kliknięciu linku weryfikacyjnego
 * ({@link RegistrationService#verify}): wiersz w {@code users} i
 * {@link UserRegisteredEvent}, z którego reszta modułów (profil, analityka)
 * zakłada swoje dane. Rolę administratora nadajemy bezpośrednio, bo pierwszy
 * admin z założenia powstaje poza API (ADR 0021) — stąd brak wpisu w dzienniku
 * moderacji.
 *
 * <p>
 * Idempotentne: istniejące konto zostaje nietknięte, więc restart nie resetuje
 * haseł ani tego, co ktoś zdążył przeklikać.
 */
@Component
@Profile("local")
class LocalDevAccountsSeeder implements ApplicationRunner {

	static final String ADMIN_EMAIL = "admin@songly.local";
	static final String USER_EMAIL = "user@songly.local";
	static final String PASSWORD = "Songly123!";

	private static final Logger log = LoggerFactory.getLogger(LocalDevAccountsSeeder.class);

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final ApplicationEventPublisher eventPublisher;

	LocalDevAccountsSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder,
			ApplicationEventPublisher eventPublisher) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.eventPublisher = eventPublisher;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		seed(ADMIN_EMAIL, true);
		seed(USER_EMAIL, false);
	}

	private void seed(String email, boolean admin) {
		if (userRepository.existsByEmail(email)) {
			return;
		}
		Instant now = Instant.now();
		User user = new User(UUID.randomUUID(), email, passwordEncoder.encode(PASSWORD), now);
		user.changeAdmin(admin);
		userRepository.save(user);
		eventPublisher.publishEvent(new UserRegisteredEvent(user.getId(), user.getEmail(), now));
		log.warn("Profil local: założono konto testowe {} (admin={})", user.getId(), admin);
	}
}
