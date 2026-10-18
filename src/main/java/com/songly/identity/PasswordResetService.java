package com.songly.identity;

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
 * Reset hasła dla użytkownika, który go nie pamięta — jedyna ścieżka powrotu do
 * konta, której do tej pory nie było.
 *
 * <p>
 * Brak tej ścieżki nie był drobiazgiem: mechanizm z Claude.md §9 (wykrywanie
 * ponownego użycia refresh tokenu) wylogowuje ze <b>wszystkich</b> urządzeń i
 * opiera się wprost na założeniu, że „użytkownik wraca hasłem, którego złodziej
 * nie ma". Bez resetu to założenie było fałszywe dla każdego, kto hasła nie
 * pamiętał — zabezpieczenie przed kradzieżą kończyło się trwałą utratą konta.
 *
 * <p>
 * <b>Żaden z dwóch endpointów nie zdradza, czy konto istnieje.</b> Żądanie
 * resetu zawsze kończy się tak samo (202), a odmowa przy podmianie hasła zawsze
 * tym samym kodem błędu. Endpoint resetu jest publiczny i nielimitowany
 * tożsamością pytającego, więc każde rozróżnienie w odpowiedzi byłoby darmową
 * listą kont — dokładnie to, przed czym chroni już logowanie (§13).
 */
@Service
@EnableConfigurationProperties(PasswordResetProperties.class)
class PasswordResetService {

	private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

	/**
	 * {@code songly_auth_password_reset_attempts_total{outcome="..."}}.
	 *
	 * <p>
	 * Serie celowo <b>nie</b> rozdzielają „konto nie istnieje" od „konto istnieje":
	 * zgodnie z §13 nie budujemy nigdzie licznika, z którego dałoby się odczytać
	 * cokolwiek o istnieniu kont. Do wykrycia nadużycia wystarczy {@code throttled}
	 * rosnące szybciej niż {@code requested}.
	 */
	private static final String RESET_ATTEMPTS_METRIC = "songly.auth.password-reset.attempts";

	private final UserRepository userRepository;
	private final PasswordResetTokenRepository tokenRepository;
	private final SessionRepository sessionRepository;
	private final PasswordEncoder passwordEncoder;
	private final PasswordResetProperties properties;
	private final ApplicationEventPublisher eventPublisher;
	private final MeterRegistry meterRegistry;

	PasswordResetService(UserRepository userRepository, PasswordResetTokenRepository tokenRepository,
			SessionRepository sessionRepository, PasswordEncoder passwordEncoder, PasswordResetProperties properties,
			ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry) {
		this.userRepository = userRepository;
		this.tokenRepository = tokenRepository;
		this.sessionRepository = sessionRepository;
		this.passwordEncoder = passwordEncoder;
		this.properties = properties;
		this.eventPublisher = eventPublisher;
		this.meterRegistry = meterRegistry;
	}

	/**
	 * Zamawia link resetujący. Wraca bez wyniku i <b>zawsze</b> tak samo — czy
	 * konto istnieje, czy nie, czy właśnie wyciszył je throttling.
	 *
	 * <p>
	 * Nowe żądanie unieważnia poprzedni link (kasujemy stary wiersz). To nie tylko
	 * higiena tabeli: bez tego każde kolejne „nie dostałem maila" zostawiałoby w
	 * obiegu jeszcze jeden ważny klucz do konta, a użytkownik nie miałby żadnego
	 * sposobu, żeby je unieważnić.
	 */
	@Transactional
	void requestReset(String email) {
		resetOutcome("requested").increment();
		Optional<User> found = userRepository.findByEmail(email);
		if (found.isEmpty()) {
			// Celowo bez logu i bez osobnej metryki — jedno i drugie byłoby zapisem
			// „pod tym adresem nie ma konta", czyli tą samą wyrocznią, tylko przeniesioną
			// z odpowiedzi HTTP do systemu, którego RODO dotyczy tak samo (§9).
			return;
		}
		User user = found.get();
		Instant now = Instant.now();

		Optional<PasswordResetToken> previous = tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId());
		if (previous.isPresent() && previous.get().getCreatedAt().isAfter(now.minus(properties.getResendInterval()))) {
			resetOutcome("throttled").increment();
			log.info("Wyciszone żądanie resetu hasła (odstęp {}), konto: {}", properties.getResendInterval(),
					user.getId());
			return;
		}
		tokenRepository.deleteByUserId(user.getId());

		String token = SecureTokens.generate();
		tokenRepository.save(new PasswordResetToken(UUID.randomUUID(), user.getId(), SecureTokens.hash(token), now,
				now.plus(properties.getTtl())));
		log.info("Wydano token resetu hasła dla konta {}, ważny {}", user.getId(), properties.getTtl());
		eventPublisher.publishEvent(new PasswordResetRequestedEvent(user.getId(), user.getEmail(), token));
	}

	/**
	 * Ustawia nowe hasło na podstawie tokenu z linku.
	 *
	 * <p>
	 * Skutki są celowo takie same jak przy zmianie hasła (§9): <b>wszystkie sesje
	 * giną</b>. Reset jest tym, co robi się po utracie kontroli nad kontem, więc
	 * zostawienie w mocy sesji sprzed resetu przekreślałoby całą operację. Kasujemy
	 * też pozostałe tokeny resetu tego konta — nie tylko ten użyty — inaczej
	 * równolegle zamówiony link dalej otwierałby konto po tym, jak prawowity
	 * właściciel już je odzyskał.
	 *
	 * <p>
	 * Kolejność ma znaczenie i nie jest kosmetyczna: {@code revokeAllForUser} ma
	 * {@code clearAutomatically}, więc czyści kontekst persystencji. Zmiana hasła
	 * musi być <b>przed</b> nim (dzięki {@code flushAutomatically} trafia wtedy do
	 * bazy), a kasowanie tokenów <b>po</b> nim — encja wczytana wcześniej jest już
	 * odłączona. Ta sama pułapka, którą opisuje {@code SessionRepository}.
	 */
	@Transactional
	void resetPassword(String token, String newPassword) {
		PasswordResetToken resetToken = tokenRepository.findByTokenHash(SecureTokens.hash(token))
				.orElseThrow(this::invalidToken);
		if (resetToken.isExpired(Instant.now())) {
			throw invalidToken();
		}
		UUID userId = resetToken.getUserId();
		User user = userRepository.findById(userId).orElseThrow(this::invalidToken);

		user.changePassword(passwordEncoder.encode(newPassword));
		int revoked = sessionRepository.revokeAllForUser(userId, Instant.now());
		eventPublisher.publishEvent(new UserSessionsRevokedEvent(userId));
		tokenRepository.deleteByUserId(userId);

		resetOutcome("success").increment();
		log.info("Reset hasła konta {}, odwołane sesje: {}", userId, revoked);
	}

	private InvalidPasswordResetTokenException invalidToken() {
		resetOutcome("invalid-token").increment();
		log.warn("Odrzucony token resetu hasła");
		return new InvalidPasswordResetTokenException();
	}

	private Counter resetOutcome(String outcome) {
		return meterRegistry.counter(RESET_ATTEMPTS_METRIC, "outcome", outcome);
	}
}
