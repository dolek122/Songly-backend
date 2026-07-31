package com.songly.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

	Optional<PasswordResetToken> findByTokenHash(String tokenHash);

	Optional<PasswordResetToken> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

	/**
	 * Kasowanie po encjach (a nie masowym {@code @Modifying}), bo liczba tokenów
	 * jednego użytkownika jest z założenia bliska jedności — nowe żądanie resetu
	 * usuwa poprzedni. Dzięki temu tabela nie potrzebuje osobnego zadania
	 * sprzątającego, w przeciwieństwie do {@code sessions}.
	 */
	void deleteByUserId(UUID userId);
}
