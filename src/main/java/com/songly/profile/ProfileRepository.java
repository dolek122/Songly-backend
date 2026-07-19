package com.songly.profile;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProfileRepository extends JpaRepository<Profile, UUID> {

	Optional<Profile> findByUserId(UUID userId);

	boolean existsByUserId(UUID userId);

	List<Profile> findByUserIdIn(Collection<UUID> userIds);
}
