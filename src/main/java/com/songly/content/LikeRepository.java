package com.songly.content;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LikeRepository extends JpaRepository<Like, LikeId> {

	long countByPostId(UUID postId);

	/** Zliczenia dla całej strony postów jednym zapytaniem (bez N+1). */
	@Query("select l.postId as postId, count(l) as likeCount from Like l where l.postId in :postIds group by l.postId")
	List<PostIdCount> countByPostIdIn(@Param("postIds") Collection<UUID> postIds);

	List<Like> findByPostIdInAndUserId(Collection<UUID> postIds, UUID userId);

	interface PostIdCount {

		UUID getPostId();

		long getLikeCount();
	}
}
