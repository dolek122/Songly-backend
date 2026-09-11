package com.songly.community;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CommunityMemberRepository extends JpaRepository<CommunityMember, CommunityMemberId> {

	Page<CommunityMember> findByCommunityIdOrderByJoinedAtAsc(UUID communityId, Pageable pageable);

	/** Adresaci powiadomień moderacyjnych — właściciel i moderatorzy. */
	List<CommunityMember> findByCommunityIdAndRoleIn(UUID communityId, Collection<CommunityRole> roles);

	Page<CommunityMember> findByUserIdOrderByJoinedAtDesc(UUID userId, Pageable pageable);

	List<CommunityMember> findByUserIdAndCommunityIdIn(UUID userId, Collection<UUID> communityIds);

	long countByCommunityId(UUID communityId);

	void deleteByUserId(UUID userId);

	/** Zliczenia dla całej strony społeczności jednym zapytaniem (bez N+1). */
	@Query("select m.communityId as communityId, count(m) as memberCount from CommunityMember m "
			+ "where m.communityId in :communityIds group by m.communityId")
	List<CommunityIdCount> countByCommunityIdIn(@Param("communityIds") Collection<UUID> communityIds);

	interface CommunityIdCount {

		UUID getCommunityId();

		long getMemberCount();
	}
}
