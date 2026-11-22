package com.songly.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AccountDeletionTokenRepository extends JpaRepository<AccountDeletionToken, UUID> {

	Optional<AccountDeletionToken> findByTokenHash(String tokenHash);

	Optional<AccountDeletionToken> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

	void deleteByUserId(UUID userId);
}
