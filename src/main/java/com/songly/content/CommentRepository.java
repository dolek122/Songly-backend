package com.songly.content;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CommentRepository extends JpaRepository<Comment, UUID> {

	Page<Comment> findByPostIdOrderByCreatedAtAsc(UUID postId, Pageable pageable);

	long countByPostId(UUID postId);

	/** Zliczenia dla całej strony postów jednym zapytaniem (bez N+1). */
	@Query("select c.postId as postId, count(c) as commentCount from Comment c where c.postId in :postIds group by c.postId")
	List<PostIdCount> countByPostIdIn(@Param("postIds") Collection<UUID> postIds);

	interface PostIdCount {

		UUID getPostId();

		long getCommentCount();
	}
}
