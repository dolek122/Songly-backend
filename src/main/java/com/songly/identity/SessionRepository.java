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
	 * Kasuje porcję sesji, które nie mogą już nikogo uwierzytelnić: odwołanych
	 * dawniej niż {@code cutoff} albo wygasłych przed nim.
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
			    WHERE (revoked_at IS NOT NULL AND revoked_at < :cutoff) OR expires_at < :cutoff
			    LIMIT :batchSize
			)
			""")
	int deleteDeadSessions(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
