package com.songly.content;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ReporterDecisionRepository extends JpaRepository<ReporterDecision, UUID> {

	/**
	 * Historia jednej osoby po wszystkich społecznościach — do policzenia
	 * przydziału (ADR 0025: limit jest globalny, bo zasypywanie rozkłada się na
	 * wiele społeczności).
	 *
	 * <p>
	 * Cięcie po {@code decidedAt} jest tą „jedną linią w zapytaniu", którą ADR 0025
	 * zapowiedział jako naturalną korektę (ADR 0030). Okno jest <b>przesuwane</b>:
	 * żadna decyzja nie wygasa wcześniej niż po pełnym oknie.
	 */
	long countByUserIdAndOutcomeAndDecidedAtAfter(UUID userId, ReporterDecisionOutcome outcome, Instant decidedAt);

	/**
	 * Historia całej strony zgłaszających w <b>jednej</b> społeczności — bez N+1.
	 * Zasięg węższy niż przy przydziale, bo moderator nie ma prawa wiedzieć, co ten
	 * człowiek robił w obcych społecznościach (Claude.md §8).
	 *
	 * <p>
	 * Rozstrzygnięcie jest parametrem, a nie kolumną w projekcji, i to nie jest
	 * kwestia gustu: Spring Data realizuje projekcję proxy JDK, a ono nie ma
	 * dostępu do typu <b>package-private</b> — enum w roli gettera wywala zapytanie
	 * {@code IllegalAccessError} dopiero w czasie wykonania. Dwa zapytania po
	 * jednym indeksie są tańsze niż upublicznienie typu, którego nie potrzebuje
	 * żaden inny moduł.
	 */
	@Query("""
			select d.userId as userId, count(d) as decisionCount
			from ReporterDecision d
			where d.communityId = :communityId and d.outcome = :outcome
			  and d.userId in :userIds and d.decidedAt > :since
			group by d.userId
			""")
	List<UserDecisionCount> countByCommunityAndOutcomeSince(@Param("communityId") UUID communityId,
			@Param("outcome") ReporterDecisionOutcome outcome, @Param("userIds") Collection<UUID> userIds,
			@Param("since") Instant since);

	@Modifying(flushAutomatically = true)
	@Query("delete from ReporterDecision d where d.userId = :userId")
	void deleteByUserId(@Param("userId") UUID userId);

	@Modifying(flushAutomatically = true)
	@Query("delete from ReporterDecision d where d.communityId = :communityId")
	void deleteByCommunityId(@Param("communityId") UUID communityId);

	interface UserDecisionCount {

		UUID getUserId();

		long getDecisionCount();
	}
}
