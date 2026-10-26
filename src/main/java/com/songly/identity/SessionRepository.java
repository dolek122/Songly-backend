package com.songly.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface SessionRepository extends JpaRepository<Session, UUID> {

	Optional<Session> findByRefreshTokenHash(String refreshTokenHash);

	void deleteByUserId(UUID userId);

	/**
	 * Odwołuje wszystkie żywe sesje użytkownika (zmiana hasła). Aktualizacja
	 * masowa, nie pętla po encjach: {@code Session} nie ma cyklu życia, na którym
	 * cokolwiek wisi, a liczba sesji jest nieograniczona z góry.
	 *
	 * <p>
	 * {@code clearAutomatically}, bo zapytanie masowe omija kontekst persystencji —
	 * bez tego encje sesji wczytane wcześniej w tej samej transakcji zostałyby w
	 * pamięci jako nieodwołane.
	 *
	 * <p>
	 * <b>{@code flushAutomatically} jest tu warunkiem poprawności, nie
	 * optymalizacją.</b> Wołający ({@code changePassword}) zmienia hasło na encji
	 * {@code User} tuż przed tym wywołaniem. Bez wymuszonego flusha czyszczenie
	 * kontekstu wyrzuciłoby tę zmianę razem z resztą — hasło nigdy nie dotarłoby do
	 * bazy, a stare dalej by działało. Objaw byłby przy tym cichy: endpoint zwraca
	 * 204, sesje faktycznie giną, tylko samo hasło zostaje niezmienione.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Session s set s.revoked = true, s.revokedAt = :now where s.userId = :userId and s.revoked = false")
	int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

	/**
	 * Kasuje porcję sesji wygasłych przed {@code cutoff}.
	 *
	 * <p>
	 * <b>Odwołana sesja zostaje do swojego {@code expires_at}</b> i to jest warunek
	 * wykrywania kradzieży tokenu, nie ostrożność. Rotacja odwołuje token przy
	 * każdym odświeżeniu, a {@code AuthService.refresh} rozpoznaje skradzioną kopię
	 * wyłącznie po tym, że znajduje jej wiersz jako odwołany. Wcześniej odwołane
	 * wiersze znikały po tygodniu: prawowity użytkownik, który wrócił po ośmiu
	 * dniach ze starym tokenem, dostawał zwykłe „nieznany token”, a łańcuch sesji
	 * złodzieja żył dalej. Odwołany, ale jeszcze ważny token jest tym samym dowodem
	 * kopii po tygodniu co po minucie.
	 *
	 * <p>
	 * Zapytanie natywne, bo JPQL nie zna {@code LIMIT}, a limit jest tu istotny:
	 * bez niego pierwszy przebieg na zaniedbanej tabeli robiłby jeden gigantyczny
	 * DELETE, blokując wiersze na czas całej transakcji. Porcjami tabela drenuje
	 * się przez kilka przebiegów i nikt tego nie zauważa.
	 *
	 * <p>
	 * {@code @Transactional} jest tutaj, a nie na wołającym: {@code @Modifying}
	 * wymaga transakcji, a gdyby otwierał ją job, wszystkie porcje commitowałyby
	 * się dopiero na jego końcu — czyli dzielenie na porcje nie dawałoby niczego.
	 */
	@Modifying
	@Transactional
	@Query(nativeQuery = true, value = """
			DELETE FROM sessions WHERE id IN (
			    SELECT id FROM sessions
			    WHERE expires_at < :cutoff
			    LIMIT :batchSize
			)
			""")
	int deleteDeadSessions(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
