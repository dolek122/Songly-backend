package com.songly.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface PendingRegistrationRepository extends JpaRepository<PendingRegistration, UUID> {

	Optional<PendingRegistration> findByEmail(String email);

	Optional<PendingRegistration> findByTokenHash(String tokenHash);

	/**
	 * Kasuje zgłoszenia po terminie — patrz {@link PendingRegistrationCleanupJob}.
	 * Masowo, bo na tej encji nie wisi żaden cykl życia, a {@code @Transactional}
	 * tutaj z tego samego powodu co przy sprzątaniu sesji: job nie otwiera
	 * transakcji sam.
	 */
	@Modifying
	@Transactional
	@Query("delete from PendingRegistration p where p.expiresAt < :now")
	int deleteExpired(@Param("now") Instant now);
}
