package com.songly.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AccountDeletionTokenRepository extends JpaRepository<AccountDeletionToken, UUID> {

	Optional<AccountDeletionToken> findByTokenHash(String tokenHash);

	Optional<AccountDeletionToken> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

	/**
	 * Po encjach, jak przy tokenach resetu: na konto przypada co najwyżej jeden
	 * żywy token, bo nowe żądanie kasuje poprzedni.
	 */
	void deleteByUserId(UUID userId);
}
