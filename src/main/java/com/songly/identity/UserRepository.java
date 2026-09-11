package com.songly.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

	boolean existsByIdAndAdminTrue(UUID id);

	/**
	 * Pełny wykaz administratorów (ADR 0021) — celowo bez paginacji, patrz
	 * {@code AdminRoleService#admins}.
	 */
	List<User> findByAdminTrueOrderByCreatedAtAsc();

	/**
	 * Konta z jakimkolwiek zawieszeniem — także wygasłym, bo ten warunek wyraża się
	 * w SQL-u jedną alternatywą mniej, a odsianie wygasłych w Javie jest tu tanie:
	 * zawieszonych kont jest z natury niewiele (ADR 0022).
	 */
	List<User> findBySuspendedAtIsNotNullOrderBySuspendedAtDesc();
}
