package com.songly.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SessionRepository extends JpaRepository<Session, UUID> {

	Optional<Session> findByRefreshTokenHash(String refreshTokenHash);

	void deleteByUserId(UUID userId);
}
