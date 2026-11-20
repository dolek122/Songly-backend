package com.songly.profile;

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface GenreRepository extends JpaRepository<Genre, UUID> {

	long countByIdIn(Collection<UUID> ids);

	/**
	 * Kolejność w nazwie metody, nie w {@code Pageable}: sortowanie od klienta jest
	 * ignorowane (ADR 0051), a {@code findAll(pageable)} bez sortowania dawał
	 * kolejność, jaką akurat zwróci Postgres, czyli strony mogły się nakładać.
	 */
	Page<Genre> findAllByOrderByNameAsc(Pageable pageable);
}
