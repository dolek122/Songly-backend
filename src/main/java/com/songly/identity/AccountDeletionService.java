package com.songly.identity;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Usuwanie konta bez aplikacji (ADR 0043): prośba z publicznej strony WWW,
 * tożsamość potwierdzona linkiem z maila.
 *
 * <p>
 * Google Play wymaga, żeby o usunięcie konta dało się poprosić także spoza
 * aplikacji — kto ją odinstalował, nie może musieć jej instalować z powrotem.
 * Hasło w formularzu nie wystarczy: kto go nie pamięta, musiałby najpierw
 * zrobić reset, a link resetu otwiera aplikację ({@code songly://}, ADR 0037).
 * Dostęp do skrzynki to ten sam dowód, którym reset hasła i tak oddaje całe
 * konto, więc nie obniża poziomu ochrony.
 *
 * <p>
 * <b>Prośba nie mówi, czy konto istnieje</b> — ta sama zasada i ten sam kształt
 * co reset hasła (ADR 0008): zawsze ta sama odpowiedź, bez logu i metryki dla
 * nieznanego adresu, odstęp między wysyłkami na to samo konto, jeden żywy link.
 */
@Service
@EnableConfigurationProperties(AccountDeletionProperties.class)
class AccountDeletionService {

	private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

	/**
	 * {@code songly_auth_account_deletion_attempts_total{outcome="..."}}. Tak jak
	 * przy resecie hasła, bez serii „konto nie istnieje" (Claude.md §13).
	 */
	private static final String ATTEMPTS_METRIC = "songly.auth.account-deletion.attempts";

	private final UserRepository userRepository;
	private final AccountDeletionTokenRepository tokenRepository;
	private final AccountEraser accountEraser;
	private final AccountDeletionProperties properties;
	private final ApplicationEventPublisher eventPublisher;
	private final MeterRegistry meterRegistry;

	AccountDeletionService(UserRepository userRepository, AccountDeletionTokenRepository tokenRepository,
			AccountEraser accountEraser, AccountDeletionProperties properties, ApplicationEventPublisher eventPublisher,
			MeterRegistry meterRegistry) {
		this.userRepository = userRepository;
		this.tokenRepository = tokenRepository;
		this.accountEraser = accountEraser;
		this.properties = properties;
		this.eventPublisher = eventPublisher;
		this.meterRegistry = meterRegistry;
	}

	@Transactional
	void requestDeletion(String email) {
		outcome("requested");
		Optional<User> found = userRepository.findByEmail(email);
		if (found.isEmpty()) {
			// Bez logu i bez metryki — patrz PasswordResetService#requestReset.
			return;
		}
		User user = found.get();
		Instant now = Instant.now();
		Optional<AccountDeletionToken> previous = tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId());
		if (previous.isPresent() && previous.get().getCreatedAt().isAfter(now.minus(properties.getResendInterval()))) {
			outcome("throttled");
			return;
		}
		tokenRepository.deleteByUserId(user.getId());
		String token = SecureTokens.generate();
		tokenRepository.save(new AccountDeletionToken(UUID.randomUUID(), user.getId(), SecureTokens.hash(token), now,
				now.plus(properties.getTtl())));
		log.info("Wydano link usunięcia konta {}, ważny {}", user.getId(), properties.getTtl());
		eventPublisher.publishEvent(new AccountDeletionLinkRequestedEvent(user.getId(), user.getEmail(), token));
	}

	/**
	 * Czy link z maila jeszcze działa — strona potwierdzenia pokazuje przycisk
	 * tylko wtedy. Mówi to wyłącznie posiadaczowi tokenu, czyli komuś, kto i tak
	 * może konto usunąć.
	 */
	@Transactional(readOnly = true)
	boolean isValid(String token) {
		return validToken(token).isPresent();
	}

	/**
	 * Usuwa konto. Wołane wyłącznie z POST-a strony potwierdzenia, nigdy z
	 * kliknięcia w link: skanery poczty i podglądy linków otwierają adresy z
	 * wiadomości same, więc GET kasujący konto robiłby to bez udziału człowieka.
	 *
	 * <p>
	 * Zawieszenie konta (ADR 0022) niczego tu nie blokuje — sankcja nie może
	 * unieważniać prawa z RODO, tak samo jak przy {@code DELETE /auth/me}.
	 *
	 * @return {@code false}, gdy token jest nieznany, wygasły albo już użyty
	 */
	@Transactional
	boolean confirmDeletion(String token) {
		Optional<AccountDeletionToken> deletionToken = validToken(token);
		if (deletionToken.isEmpty()) {
			outcome("invalid-token");
			return false;
		}
		UUID userId = deletionToken.get().getUserId();
		Optional<User> user = userRepository.findById(userId);
		if (user.isEmpty()) {
			outcome("invalid-token");
			return false;
		}
		log.warn("Usuwanie konta {} potwierdzone linkiem z maila (RODO)", userId);
		accountEraser.erase(user.get());
		outcome("deleted");
		return true;
	}

	private Optional<AccountDeletionToken> validToken(String token) {
		if (token == null || token.isBlank()) {
			return Optional.empty();
		}
		Instant now = Instant.now();
		return tokenRepository.findByTokenHash(SecureTokens.hash(token)).filter(found -> !found.isExpired(now));
	}

	private void outcome(String outcome) {
		meterRegistry.counter(ATTEMPTS_METRIC, "outcome", outcome).increment();
	}
}
