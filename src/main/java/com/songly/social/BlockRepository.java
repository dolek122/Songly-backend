package com.songly.social;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface BlockRepository extends JpaRepository<Block, BlockId> {

	Page<Block> findByBlockerIdOrderByCreatedAtDesc(UUID blockerId, Pageable pageable);
}
