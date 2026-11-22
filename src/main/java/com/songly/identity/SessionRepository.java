package com.songly.identity;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface SessionRepository extends JpaRepository<Session, UUID> {

	Optional<Session> findByRefreshTokenHash(String refreshTokenHash);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Session s where s.refreshTokenHash = :hash")
	Optional<Session> findByRefreshTokenHashForUpdate(@Param("hash") String refreshTokenHash);

	void deleteByUserId(UUID userId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Session s set s.revoked = true, s.revokedAt = :now where s.userId = :userId and s.revoked = false")
	int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

	@Modifying
	@Transactional
	@Query(nativeQuery = true, value = """
			DELETE FROM sessions WHERE id IN (
			    SELECT id FROM sessions
			    WHERE expires_at < :cutoff
			    LIMIT :batchSize
			)
			""")
	int deleteDeadSessions(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
