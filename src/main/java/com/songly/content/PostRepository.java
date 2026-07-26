package com.songly.content;

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PostRepository extends JpaRepository<Post, UUID> {

	/**
	 * Wszystkie odczyty „osi czasu" pomijają posty społecznościowe: te należą do
	 * swojej społeczności, nie do publicznej listy ani profilu autora. Stąd
	 * {@code CommunityIdIsNull} w nazwach — bez tego post z niszowej społeczności
	 * wypływałby na wierzch u ludzi, którzy do niej nie należą.
	 */
	Page<Post> findByCommunityIdIsNullOrderByCreatedAtDesc(Pageable pageable);

	Page<Post> findByAuthorIdAndCommunityIdIsNullOrderByCreatedAtDesc(UUID authorId, Pageable pageable);

	Page<Post> findByCommunityIdOrderByCreatedAtDesc(UUID communityId, Pageable pageable);

	void deleteByAuthorId(UUID authorId);

	void deleteByCommunityId(UUID communityId);

	/**
	 * Feed: posty napisane albo udostępnione przez podanych użytkowników, bez
	 * postów społecznościowych.
	 *
	 * <p>
	 * Warunek na {@code communityId} dotyczy posta, nie udostępnienia — inaczej
	 * udostępnienie posta ze społeczności przemycałoby go do feedu osób, które jej
	 * nie widzą.
	 */
	@Query(value = "select p from Post p where p.communityId is null and (p.authorId in :userIds "
			+ "or exists (select 1 from Share s where s.postId = p.id and s.userId in :userIds)) "
			+ "order by p.createdAt desc", countQuery = "select count(p) from Post p where p.communityId is null "
					+ "and (p.authorId in :userIds "
					+ "or exists (select 1 from Share s where s.postId = p.id and s.userId in :userIds))")
	Page<Post> findFeedPosts(@Param("userIds") Collection<UUID> userIds, Pageable pageable);
}
