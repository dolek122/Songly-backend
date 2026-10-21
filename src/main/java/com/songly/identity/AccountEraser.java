package com.songly.identity;

import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Samo usunięcie konta i wszystkich jego danych (Claude.md §9) — bez
 * sprawdzania, kto o nie prosi.
 *
 * <p>
 * Wyciągnięte z {@code AuthService}, gdy drugą drogą do usunięcia konta stał
 * się link z maila (ADR 0043). Obie drogi różnią się wyłącznie dowodem
 * tożsamości (hasło albo dostęp do skrzynki); dwie kopie samego kasowania
 * rozjechałyby się przy pierwszej nowej tabeli z kluczem obcym do
 * {@code users}, a objawiłoby się to dopiero błędem FK na produkcji.
 *
 * <p>
 * {@code MANDATORY}, bo kasowanie musi być częścią transakcji wołającego:
 * listenery pozostałych modułów sprzątają w niej synchronicznie, zanim zniknie
 * wiersz {@code users}.
 */
@Component
class AccountEraser {

	private final UserRepository userRepository;
	private final SessionRepository sessionRepository;
	private final PasswordResetTokenRepository passwordResetTokenRepository;
	private final AccountDeletionTokenRepository accountDeletionTokenRepository;
	private final ApplicationEventPublisher eventPublisher;

	AccountEraser(UserRepository userRepository, SessionRepository sessionRepository,
			PasswordResetTokenRepository passwordResetTokenRepository,
			AccountDeletionTokenRepository accountDeletionTokenRepository, ApplicationEventPublisher eventPublisher) {
		this.userRepository = userRepository;
		this.sessionRepository = sessionRepository;
		this.passwordResetTokenRepository = passwordResetTokenRepository;
		this.accountDeletionTokenRepository = accountDeletionTokenRepository;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * Event leci PRZED usunięciem wiersza {@code users}: pozostałe moduły mają FK
	 * do {@code users} bez CASCADE, więc muszą posprzątać swoje dane w tej samej
	 * transakcji, zanim to konto w ogóle da się usunąć.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	void erase(User user) {
		UUID userId = user.getId();
		eventPublisher.publishEvent(new UserAccountDeletionRequestedEvent(userId));
		sessionRepository.deleteByUserId(userId);
		// Tokeny tego modułu mają FK do users bez CASCADE, dokładnie jak sesje — konto
		// z niewykorzystanym linkiem nie dałoby się usunąć. To nie leci przez
		// zdarzenie, bo te tabele należą do tego samego modułu; zdarzenie istnieje po
		// to, żeby nie sięgać do cudzych (Claude.md §9).
		passwordResetTokenRepository.deleteByUserId(userId);
		accountDeletionTokenRepository.deleteByUserId(userId);
		userRepository.delete(user);
	}
}
