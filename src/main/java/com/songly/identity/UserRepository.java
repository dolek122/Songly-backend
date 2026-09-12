package com.songly.identity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

	/**
	 * Identyfikatory kont z <b>aktywnym</b> zawieszeniem (ADR 0026). Projekcja na
	 * same identyfikatory, nie encje: to zapytanie idzie raz na każde żądanie
	 * odczytu w modułach content i profile, więc nie ma powodu wczytywać przy tym
	 * haseł ani powodów zawieszeń.
	 */
	@Query("""
			select u.id from User u
			where u.suspendedAt is not null and (u.suspensionExpiresAt is null or u.suspensionExpiresAt > :now)
			""")
	List<UUID> findSuspendedIds(@Param("now") Instant now);
}
