package com.songly.profile;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ProfileRepository extends JpaRepository<Profile, UUID> {

	Optional<Profile> findByUserId(UUID userId);

	boolean existsByUserId(UUID userId);

	List<Profile> findByUserIdIn(Collection<UUID> userIds);

	@Query("""
			select p from Profile p
			where lower(p.displayName) like lower(concat('%', :displayName, '%')) escape '\\'
			and p.userId not in :excludedUserIds
			order by lower(p.displayName), p.userId
			""")
	Page<Profile> searchByDisplayName(@Param("displayName") String displayName,
			@Param("excludedUserIds") Collection<UUID> excludedUserIds, Pageable pageable);
}
