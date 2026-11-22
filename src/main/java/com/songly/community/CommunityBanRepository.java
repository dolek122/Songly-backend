package com.songly.community;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CommunityBanRepository extends JpaRepository<CommunityBan, CommunityBanId> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select b from CommunityBan b where b.communityId = :communityId and b.userId = :userId")
	Optional<CommunityBan> findForUpdate(@Param("communityId") UUID communityId, @Param("userId") UUID userId);

	@Query("""
			select count(b) > 0 from CommunityBan b
			where b.communityId = :communityId and b.userId = :userId
			and (b.expiresAt is null or b.expiresAt > :now)
			""")
	boolean existsActive(@Param("communityId") UUID communityId, @Param("userId") UUID userId,
			@Param("now") Instant now);

	@Query("""
			select b from CommunityBan b
			where b.communityId = :communityId and (b.expiresAt is null or b.expiresAt > :now)
			order by b.createdAt desc
			""")
	Page<CommunityBan> findActiveByCommunityId(@Param("communityId") UUID communityId, @Param("now") Instant now,
			Pageable pageable);

	List<CommunityBan> findByAppealStatusOrderByAppealEscalatedAtAsc(BanAppealStatus appealStatus);

	@Modifying(flushAutomatically = true)
	@Query("delete from CommunityBan b where b.userId = :userId")
	void deleteByUserId(@Param("userId") UUID userId);

	@Modifying(flushAutomatically = true)
	@Query("update CommunityBan b set b.bannedBy = null where b.bannedBy = :bannedBy")
	void clearBannedBy(@Param("bannedBy") UUID bannedBy);
}
