package com.songly.nowplaying;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface NowPlayingPreferenceRepository extends JpaRepository<NowPlayingPreference, UUID> {

	@Query("select p.userId from NowPlayingPreference p where p.userId in :userIds and p.shareWithFriends = false")
	Set<UUID> findOptedOutUserIds(@Param("userIds") Collection<UUID> userIds);

	boolean existsByUserIdAndShareWithFriendsFalse(UUID userId);

	void deleteByUserId(UUID userId);
}
