package com.songly.community;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface CommunityRepository extends JpaRepository<Community, UUID> {

	Page<Community> findAllByOrderByNameAsc(Pageable pageable);

	boolean existsByNameIgnoreCase(String name);
}
