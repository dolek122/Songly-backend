package com.songly.identity;

import java.time.Instant;
import java.util.Optional;
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
	 * Pusty wynik znaczy, że konta już nie ma: token wystawiono przed jego
	 * usunięciem. Filtr traktuje go wtedy jak każdy nieważny token (ADR 0052).
	 * Wcześniej takie konto dostawało zwykłą rolę użytkownika, bo zakładaliśmy, że
	 * moduły i tak nie znajdą jego danych. Nie musiały ich znaleźć: feed,
	 * wyszukiwarka, ręczny status i strumień SSE czytają cudze dane albo piszą
	 * nowe, więc działały do wygaśnięcia tokenu.
	 */
	@Transactional(readOnly = true)
	public Optional<AccountStatus> statusOf(UUID userId) {
		Instant now = Instant.now();
		return userRepository.findById(userId).map(user -> new AccountStatus(user.isAdmin(), user.isSuspendedAt(now)));
	}
}
