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

	/**
	 * W Prometheusie: {@code songly_auth_login_attempts_total{outcome="..."}} —
	 * nagły skok {@code failure} to sygnał brute-force'u, widoczny bez parsowania
	 * logów.
	 */
	private static final String LOGIN_ATTEMPTS_METRIC = "songly.auth.login.attempts";

	/**
	 * {@code songly_auth_refresh_attempts_total{outcome="reuse"}} to sygnał
	 * kradzieży tokenu — w zdrowym ruchu ta seria stoi na zerze, więc każdy jej
	 * przyrost jest wart uwagi.
	 *
	 * <p>
	 * Powody niepowodzenia rozdzielamy tutaj, w przeciwieństwie do logowania
	 * ({@code outcome} przy loginie celowo nie mówi, czy konto istnieje).
	 * Rozróżnienie „reuse" od „invalid" nie zdradza niczego o cudzych kontach —
	 * mówi o stanie ataku, i o to właśnie chodzi.
	 */
	private static final String REFRESH_ATTEMPTS_METRIC = "songly.auth.refresh.attempts";

	private final UserRepository userRepository;
	private final SessionRepository sessionRepository;
	private final PasswordResetTokenRepository passwordResetTokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final TokenIssuer tokenIssuer;
	private final ApplicationEventPublisher eventPublisher;
	private final MeterRegistry meterRegistry;

	/**
	 * Skrót, z którym porównujemy hasło, gdy konta nie ma (ADR 0041). Liczony raz
	 * przy starcie tym samym enkoderem co prawdziwe hasła, więc ma ten sam koszt
	 * bcrypta.
	 */
	private final String unknownAccountHash;

	AuthService(UserRepository userRepository, SessionRepository sessionRepository,
			PasswordResetTokenRepository passwordResetTokenRepository, PasswordEncoder passwordEncoder,
			TokenIssuer tokenIssuer, ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry) {
		this.userRepository = userRepository;
		this.sessionRepository = sessionRepository;
		this.passwordResetTokenRepository = passwordResetTokenRepository;
		this.passwordEncoder = passwordEncoder;
		this.tokenIssuer = tokenIssuer;
		this.eventPublisher = eventPublisher;
		this.meterRegistry = meterRegistry;
		this.unknownAccountHash = passwordEncoder.encode(SecureTokens.generate());
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
	 *
	 * <p>
	 * <b>Przy nieznanym koncie też liczymy bcrypt</b> (ADR 0041). Ten sam kod 401
	 * dla obu porażek nie wystarcza, jeśli jedna kosztuje zapytanie, a druga
	 * dodatkowo bcrypt: czas odpowiedzi sam mówiłby, czy pod adresem jest konto.
	 * Rejestracja broni się przed tym tak samo (ADR 0009).
	 */
	@Transactional
	AuthTokensResponse login(LoginRequest request) {
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

	/**
	 * Rotacja refresh tokenu z <b>wykrywaniem ponownego użycia</b>. Sama rotacja
	 * (odwołanie starego tokenu przy wydaniu nowego) daje mniej, niż się wydaje:
	 * bez tego sprawdzenia skradziony token dawał złodziejowi ważną sesję, a
	 * prawowitemu użytkownikowi tylko ciche 401 przy następnym odświeżeniu — nikt
	 * nie miał jak zauważyć, że doszło do kradzieży.
	 *
	 * <p>
	 * Token po rotacji istnieje w jednym egzemplarzu i wolno go użyć raz. Podanie
	 * <b>odwołanego</b> tokenu znaczy więc, że krąży kopia — nie wiadomo tylko, po
	 * której stronie. Dlatego odwołujemy <b>wszystkie</b> sesje użytkownika:
	 * złodziej traci swoją, użytkownik loguje się ponownie hasłem, którego złodziej
	 * nie ma.
	 *
	 * <p>
	 * Rozróżnienie „odwołany" od „wygasły" jest tu istotne. Wygaśnięcie to zwykły
	 * upływ czasu i nie mówi niczego o kradzieży — traktowanie go jak nadużycia
	 * wylogowywałoby ze wszystkich urządzeń każdego, kto wrócił do aplikacji po
	 * miesiącu.
	 *
	 * <p>
	 * {@code noRollbackFor} nie jest ozdobnikiem — ta sama pułapka co w
	 * {@code SpotifyProvider} (§7): rewokacja dzieje się tuż przed rzuceniem
	 * wyjątku, więc bez tego rollback cofnąłby ją w całości i wykrycie kradzieży
	 * nie zostawiłoby żadnego skutku poza wpisem w logu. Sprawdzone przez usunięcie
	 * tej flagi: czerwienieją wtedy dokładnie te dwa testy, które asertują, że
	 * rewokacja przetrwała.
	 */
	@Transactional(noRollbackFor = RefreshTokenReuseDetectedException.class)
	AuthTokensResponse refresh(RefreshRequest request) {
		String hash = SecureTokens.hash(request.refreshToken());
		Session session = sessionRepository.findByRefreshTokenHash(hash).orElseGet(() -> {
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
		eventPublisher.publishEvent(new UserSessionsRevokedEvent(userId));
		// Oczekujący link resetujący traci ważność razem ze starym hasłem. Zwykle
		// jest tam dlatego, że ktoś obcy zamówił reset — zostawienie go żywego przez
		// pół godziny po tym, jak właściciel zareagował zmianą hasła, znaczyłoby, że
		// reakcja niczego nie zamknęła.
		passwordResetTokenRepository.deleteByUserId(userId);
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
		// Tokeny resetu mają FK do users bez CASCADE, dokładnie jak sesje — konto z
		// niewykorzystanym linkiem resetującym nie dałoby się usunąć. To nie leci
		// przez zdarzenie, bo ta tabela należy do tego samego modułu; zdarzenie
		// istnieje po to, żeby nie sięgać do cudzych (Claude.md §9).
		passwordResetTokenRepository.deleteByUserId(userId);
		userRepository.delete(user);
	}

}
