package com.songly.content;

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PostRepository extends JpaRepository<Post, UUID> {

	Page<Post> findAllByOrderByCreatedAtDesc(Pageable pageable);

	Page<Post> findByAuthorIdOrderByCreatedAtDesc(UUID authorId, Pageable pageable);

	/** Feed: posty napisane albo udostępnione przez podanych użytkowników. */
	@Query(value = "select p from Post p where p.authorId in :userIds "
			+ "or exists (select 1 from Share s where s.postId = p.id and s.userId in :userIds) "
			+ "order by p.createdAt desc", countQuery = "select count(p) from Post p where p.authorId in :userIds "
					+ "or exists (select 1 from Share s where s.postId = p.id and s.userId in :userIds)")
	Page<Post> findFeedPosts(@Param("userIds") Collection<UUID> userIds, Pageable pageable);
}
