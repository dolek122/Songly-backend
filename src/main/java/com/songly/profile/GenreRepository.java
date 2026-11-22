package com.songly.profile;

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface GenreRepository extends JpaRepository<Genre, UUID> {

	long countByIdIn(Collection<UUID> ids);

	Page<Genre> findAllByOrderByNameAsc(Pageable pageable);
}
