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

	long countByUserIdAndOutcomeAndDecidedAtAfter(UUID userId, ReporterDecisionOutcome outcome, Instant decidedAt);

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

	@Query("""
			select d.userId as userId, count(d) as decisionCount
			from ReporterDecision d
			where d.communityId is null and d.outcome = :outcome
			  and d.userId in :userIds and d.decidedAt > :since
			group by d.userId
			""")
	List<UserDecisionCount> countOutsideCommunitiesByOutcomeSince(@Param("outcome") ReporterDecisionOutcome outcome,
			@Param("userIds") Collection<UUID> userIds, @Param("since") Instant since);

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
