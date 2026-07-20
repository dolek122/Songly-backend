package com.songly.content;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ShareRepository extends JpaRepository<Share, ShareId> {

	/** Zliczenia dla całej strony postów jednym zapytaniem (bez N+1). */
	@Query("select s.postId as postId, count(s) as shareCount from Share s where s.postId in :postIds group by s.postId")
	List<PostIdCount> countByPostIdIn(@Param("postIds") Collection<UUID> postIds);

	List<Share> findByPostIdInAndUserId(Collection<UUID> postIds, UUID userId);

	interface PostIdCount {

		UUID getPostId();

		long getShareCount();
	}
}
