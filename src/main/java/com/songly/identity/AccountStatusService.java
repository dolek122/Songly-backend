package com.songly.identity;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Jedyny punkt, przez który pakiet {@code security} (JwtAuthenticationFilter)
 * może sprawdzić stan konta — bez dostępu do encji User ani repozytorium
 * (Claude.md §2: encje JPA nie wychodzą poza moduł).
 *
 * <p>
 * Zastąpił {@code AdminStatusService}: od ADR 0022 filtr potrzebuje dwóch
 * odpowiedzi naraz (rola i zawieszenie), a dwa osobne serwisy znaczyłyby dwa
 * zapytania na każde żądanie.
 */
@Service
public class AccountStatusService {

	private final UserRepository userRepository;

	AccountStatusService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	/**
	 * Nieznane konto (token wystawiony przed usunięciem konta) czytamy jako
	 * {@code UNKNOWN} — bez roli i bez zawieszenia. Autoryzację i tak zatrzyma
	 * wtedy brak danych po stronie modułów, a rzucanie wyjątku z filtra zamieniłoby
	 * wygasły token w 500-tkę.
	 */
	@Transactional(readOnly = true)
	public AccountStatus statusOf(UUID userId) {
		Instant now = Instant.now();
		return userRepository.findById(userId).map(user -> new AccountStatus(user.isAdmin(), user.isSuspendedAt(now)))
				.orElse(AccountStatus.UNKNOWN);
	}
}
