package com.songly.nowplaying;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MusicProviderLinkRepository extends JpaRepository<MusicProviderLink, UUID> {

	Optional<MusicProviderLink> findByUserIdAndProvider(UUID userId, String provider);

	void deleteByUserIdAndProvider(UUID userId, String provider);
}
