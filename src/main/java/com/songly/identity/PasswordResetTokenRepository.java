package com.songly.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

	Optional<PasswordResetToken> findByTokenHash(String tokenHash);

	Optional<PasswordResetToken> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

	void deleteByUserId(UUID userId);
}
