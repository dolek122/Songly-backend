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

	/**
	 * Wyszukiwanie po fragmencie nazwy (case-insensitive) z pominięciem podanych
	 * identyfikatorów: pytającego i kont zawieszonych (ADR 0026).
	 *
	 * <p>
	 * Jedno {@code not in} zamiast dwóch warunków, bo wołający i tak musi zbudować
	 * ten zbiór — a przy rozdzieleniu łatwo dopisać filtr w jednym miejscu i
	 * zapomnieć o drugim.
	 */
	@Query("""
			select p from Profile p
			where lower(p.displayName) like lower(concat('%', :displayName, '%')) and p.userId not in :excludedUserIds
			""")
	Page<Profile> searchByDisplayName(@Param("displayName") String displayName,
			@Param("excludedUserIds") Collection<UUID> excludedUserIds, Pageable pageable);
}
