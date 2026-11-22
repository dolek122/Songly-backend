package com.songly.identity;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserRepository extends JpaRepository<User, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :id")
	Optional<User> findByIdForUpdate(@Param("id") UUID id);

	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

	boolean existsByIdAndAdminTrue(UUID id);

	List<User> findByAdminTrueOrderByCreatedAtAsc();

	List<User> findBySuspendedAtIsNotNullOrderBySuspendedAtDesc();

	@Query("""
			select u.id from User u
			where u.suspendedAt is not null and (u.suspensionExpiresAt is null or u.suspensionExpiresAt > :now)
			""")
	List<UUID> findSuspendedIds(@Param("now") Instant now);
}
