package com.songly.moderationlog;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ModerationLogRepository extends JpaRepository<ModerationLogEntry, UUID> {

	Page<ModerationLogEntry> findByCommunityIdOrderByOccurredAtDesc(UUID communityId, Pageable pageable);

	Page<ModerationLogEntry> findByOrderByOccurredAtDesc(Pageable pageable);

	Page<ModerationLogEntry> findBySubjectIdOrderByOccurredAtDesc(UUID subjectId, Pageable pageable);

	@Modifying(flushAutomatically = true)
	@Query("delete from ModerationLogEntry e where e.subjectId = :userId")
	void deleteBySubjectId(@Param("userId") UUID userId);

	@Modifying(flushAutomatically = true)
	@Query("update ModerationLogEntry e set e.actorId = null where e.actorId = :actorId")
	void clearActor(@Param("actorId") UUID actorId);

	@Modifying(flushAutomatically = true)
	@Query("delete from ModerationLogEntry e where e.communityId = :communityId")
	void deleteByCommunityId(@Param("communityId") UUID communityId);
}
