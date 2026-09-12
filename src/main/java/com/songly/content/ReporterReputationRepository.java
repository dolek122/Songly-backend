package com.songly.content;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ReporterReputationRepository extends JpaRepository<ReporterReputation, ReporterReputationId> {

	Optional<ReporterReputation> findByUserIdAndCommunityId(UUID userId, UUID communityId);

	/** Wiersze jednej społeczności dla całej strony kolejki — bez N+1. */
	List<ReporterReputation> findByCommunityIdAndUserIdIn(UUID communityId, Collection<UUID> userIds);

	/**
	 * Sumy po wszystkich społecznościach — do policzenia przydziału (ADR 0025:
	 * limit jest globalny, bo zasypywanie rozkłada się na wiele społeczności).
	 * {@code coalesce}, bo bez ani jednego wiersza {@code sum} zwraca {@code null}.
	 */
	@Query("""
			select coalesce(sum(r.upheld), 0) as upheld, coalesce(sum(r.dismissed), 0) as dismissed
			from ReporterReputation r where r.userId = :userId
			""")
	Totals sumByUserId(@Param("userId") UUID userId);

	@Modifying(flushAutomatically = true)
	@Query("delete from ReporterReputation r where r.userId = :userId")
	void deleteByUserId(@Param("userId") UUID userId);

	@Modifying(flushAutomatically = true)
	@Query("delete from ReporterReputation r where r.communityId = :communityId")
	void deleteByCommunityId(@Param("communityId") UUID communityId);

	interface Totals {

		long getUpheld();

		long getDismissed();
	}
}
