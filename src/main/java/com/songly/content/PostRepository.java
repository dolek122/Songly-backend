package com.songly.content;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface PostRepository extends JpaRepository<Post, UUID> {

	Page<Post> findAllByOrderByCreatedAtDesc(Pageable pageable);

	Page<Post> findByAuthorIdOrderByCreatedAtDesc(UUID authorId, Pageable pageable);
}
