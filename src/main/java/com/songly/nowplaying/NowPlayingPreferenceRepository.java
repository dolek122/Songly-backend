package com.songly.nowplaying;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Zapytania celowo pytają o <b>wyłączonych</b>, a nie o widocznych: brak
 * wiersza znaczy "widoczny", więc lista opt-outów jest kompletną odpowiedzią na
 * "kogo pominąć", niezależnie od tego, ilu użytkowników nigdy nie ruszyło
 * ustawień.
 */
interface NowPlayingPreferenceRepository extends JpaRepository<NowPlayingPreference, UUID> {

	@Query("select p.userId from NowPlayingPreference p where p.userId in :userIds and p.shareWithFriends = false")
	Set<UUID> findOptedOutUserIds(@Param("userIds") Collection<UUID> userIds);

	boolean existsByUserIdAndShareWithFriendsFalse(UUID userId);

	void deleteByUserId(UUID userId);
}
