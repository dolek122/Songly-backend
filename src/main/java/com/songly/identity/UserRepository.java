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
}
