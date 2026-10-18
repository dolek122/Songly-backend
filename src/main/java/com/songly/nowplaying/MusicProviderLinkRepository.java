package com.songly.nowplaying;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MusicProviderLinkRepository extends JpaRepository<MusicProviderLink, UUID> {

	Optional<MusicProviderLink> findByUserIdAndProvider(UUID userId, String provider);

	boolean existsByUserIdAndProvider(UUID userId, String provider);

	/**
	 * Same identyfikatory, bez ładowania encji z tokenami — serwerowy poll (ADR
	 * 0007, Etap 1b) potrzebuje tylko wiedzieć, kogo w ogóle da się odpytać.
	 */
	@Query("select l.userId from MusicProviderLink l where l.provider = :provider and l.userId in :userIds")
	Set<UUID> findUserIdsByProviderAndUserIdIn(@Param("provider") String provider,
			@Param("userIds") Collection<UUID> userIds);

	void deleteByUserIdAndProvider(UUID userId, String provider);

	void deleteByUserId(UUID userId);
}
