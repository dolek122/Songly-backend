package com.songly.profile;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProfileRepository extends JpaRepository<Profile, UUID> {

	Optional<Profile> findByUserId(UUID userId);

	boolean existsByUserId(UUID userId);

	List<Profile> findByUserIdIn(Collection<UUID> userIds);

	/**
	 * Wyszukiwanie po fragmencie nazwy (case-insensitive), z pominięciem samego
	 * siebie.
	 */
	Page<Profile> findByDisplayNameContainingIgnoreCaseAndUserIdNot(String displayName, UUID excludedUserId,
			Pageable pageable);
}
