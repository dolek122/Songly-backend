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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rejestracja z potwierdzeniem adresu e-mail.
 *
 * <p>
 * Do tej pory rejestracja od razu zakładała konto i wydawała tokeny, a na
 * zajęty adres odpowiadała 409 z tym adresem w treści. Była więc <b>wyrocznią
 * na istnienie kont</b> — dokładnie tym, przed czym logowanie (§13) i reset
 * hasła (§9) bronią się z osobna. Wystarczyło wpisywać kolejne adresy, żeby
 * dowiedzieć się, kto ma tu konto.
 *
 * <p>
 * Teraz oba przypadki wyglądają identycznie (202) i różnią się wyłącznie
 * <b>treścią wiadomości, którą dostaje właściciel skrzynki</b>: wolny adres
 * dostaje link potwierdzający, zajęty — informację, że ktoś próbował założyć
 * konto. Osoba pytająca nie dowiaduje się niczego, chyba że jest właścicielem
 * skrzynki, a wtedy ma prawo wiedzieć.
 *
 * <p>
 * <b>Konto nie powstaje, dopóki adres nie zostanie potwierdzony.</b> Kluczowa
 * konsekwencja architektoniczna: niezweryfikowane zgłoszenie nie jest wierszem
 * w {@code users}, więc {@code UserRegisteredEvent} po prostu leci później, a
 * żaden inny moduł nie musi się uczyć, że użytkownik bywa „niepotwierdzony".
 * Flaga na {@code users} rozlałaby ten stan po profilu, wyszukiwarce i
 * dopasowywaniu (Claude.md §2).
 */
@Service
@EnableConfigurationProperties(RegistrationProperties.class)
class RegistrationService {

	private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

	/**
	 * {@code songly_auth_registration_attempts_total{outcome="..."}}.
	 *
	 * <p>
	 * {@code existing-account} mówi „ktoś próbował się zarejestrować na adres,
	 * który już ma konto" — bez wskazania jaki. Ta seria rosnąca ponad tło jest
	 * sygnałem, że ktoś przeczesuje adresy, mimo że odpowiedzi mu tego nie
	 * ułatwiają.
	 */
	private static final String REGISTRATION_ATTEMPTS_METRIC = "songly.auth.registration.attempts";

	private final UserRepository userRepository;
	private final PendingRegistrationRepository pendingRepository;
	private final PasswordEncoder passwordEncoder;
	private final TokenIssuer tokenIssuer;
	private final RegistrationProperties properties;
	private final ApplicationEventPublisher eventPublisher;
	private final MeterRegistry meterRegistry;

	RegistrationService(UserRepository userRepository, PendingRegistrationRepository pendingRepository,
			PasswordEncoder passwordEncoder, TokenIssuer tokenIssuer, RegistrationProperties properties,
			ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry) {
		this.userRepository = userRepository;
		this.pendingRepository = pendingRepository;
		this.passwordEncoder = passwordEncoder;
		this.tokenIssuer = tokenIssuer;
		this.properties = properties;
		this.eventPublisher = eventPublisher;
		this.meterRegistry = meterRegistry;
	}

	/**
	 * Przyjmuje zgłoszenie rejestracji. Wraca bez wyniku i zawsze tak samo.
	 *
	 * <p>
	 * Hasło hashujemy <b>zanim</b> cokolwiek zapiszemy, także w gałęzi „adres
	 * zajęty" — nie, żeby je tam zapisać, tylko żeby obie ścieżki kosztowały
	 * podobnie. Bcrypt to rzędy wielkości więcej niż zapytanie o e-mail, więc bez
	 * tego czas odpowiedzi sam odtworzyłby wyrocznię, którą reszta tej metody
	 * likwiduje.
	 */
	@Transactional
	void register(RegisterRequest request) {
		registrationOutcome("requested").increment();
		String passwordHash = passwordEncoder.encode(request.password());

		Optional<User> existing = userRepository.findByEmail(request.email());
		if (existing.isPresent()) {
			User owner = existing.get();
			registrationOutcome("existing-account").increment();
			log.info("Próba rejestracji na adres istniejącego konta {}", owner.getId());
			eventPublisher
					.publishEvent(new RegistrationAttemptedOnExistingAccountEvent(owner.getId(), owner.getEmail()));
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
			// Nowy link, stare hasło — patrz PendingRegistration#reissue.
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

	/**
	 * Potwierdza adres i dopiero teraz zakłada konto.
	 *
	 * <p>
	 * Zwraca parę tokenów, czyli loguje użytkownika od razu. To nie jest wygoda
	 * kosztem bezpieczeństwa: kliknięcie w link dowodzi kontroli nad skrzynką, a
	 * hasło podał ten sam człowiek chwilę wcześniej. Kazanie mu logować się zaraz
	 * po potwierdzeniu nie sprawdzałoby niczego więcej.
	 *
	 * <p>
	 * {@code UserRegisteredEvent} leci tutaj, a nie przy zgłoszeniu — z punktu
	 * widzenia reszty aplikacji użytkownik rejestruje się w tym momencie i to jest
	 * prawda, bo wcześniej żadne konto nie istniało.
	 */
	@Transactional
	AuthTokensResponse verify(String token) {
		PendingRegistration pending = pendingRepository.findByTokenHash(SecureTokens.hash(token))
				.orElseThrow(this::invalidToken);
		if (pending.isExpired(Instant.now())) {
			throw invalidToken();
		}
		// Wyścig: ktoś mógł w międzyczasie potwierdzić ten sam adres innym
		// zgłoszeniem. Sprzątamy i odmawiamy tak samo jak przy złym tokenie —
		// osobny komunikat mówiłby pytającemu, że konto na ten adres istnieje.
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
