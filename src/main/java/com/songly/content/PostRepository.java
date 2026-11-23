package com.songly.content;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PostRepository extends JpaRepository<Post, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Post p where p.id = :id")
	Optional<Post> findByIdForUpdate(@Param("id") UUID id);

	Page<Post> findByCommunityIdIsNullAndAuthorIdNotInOrderByCreatedAtDesc(Collection<UUID> excludedAuthorIds,
			Pageable pageable);

	Page<Post> findByAuthorIdAndCommunityIdIsNullOrderByCreatedAtDesc(UUID authorId, Pageable pageable);

	Page<Post> findByCommunityIdAndAuthorIdNotInOrderByCreatedAtDesc(UUID communityId,
			Collection<UUID> excludedAuthorIds, Pageable pageable);

	void deleteByAuthorId(UUID authorId);

	void deleteByCommunityId(UUID communityId);

	@Query("select p.imageKey from Post p where p.authorId = :authorId and p.imageKey is not null")
	List<String> findImageKeysByAuthorId(@Param("authorId") UUID authorId);

	@Query("select p.imageKey from Post p where p.communityId = :communityId and p.imageKey is not null")
	List<String> findImageKeysByCommunityId(@Param("communityId") UUID communityId);

	@Query(value = "select p from Post p where p.communityId is null and p.authorId not in :excludedAuthorIds "
			+ "and (p.authorId in :userIds "
			+ "or exists (select 1 from Share s where s.postId = p.id and s.userId in :userIds)) "
			+ "order by p.createdAt desc", countQuery = "select count(p) from Post p where p.communityId is null "
					+ "and p.authorId not in :excludedAuthorIds and (p.authorId in :userIds "
					+ "or exists (select 1 from Share s where s.postId = p.id and s.userId in :userIds))")
	Page<Post> findFeedPosts(@Param("userIds") Collection<UUID> userIds,
			@Param("excludedAuthorIds") Collection<UUID> excludedAuthorIds, Pageable pageable);
}
