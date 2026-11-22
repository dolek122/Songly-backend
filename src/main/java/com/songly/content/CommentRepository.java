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

	Page<Comment> findByPostIdAndAuthorIdNotInOrderByCreatedAtAsc(UUID postId, Collection<UUID> excludedAuthorIds,
			Pageable pageable);

	long countByPostId(UUID postId);

	void deleteByAuthorId(UUID authorId);

	@Query("select c.postId as postId, count(c) as commentCount from Comment c where c.postId in :postIds "
			+ "and c.authorId not in :excludedAuthorIds group by c.postId")
	List<PostIdCount> countByPostIdIn(@Param("postIds") Collection<UUID> postIds,
			@Param("excludedAuthorIds") Collection<UUID> excludedAuthorIds);

	interface PostIdCount {

		UUID getPostId();

		long getCommentCount();
	}
}
