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
	 *
	 * <p>
	 * {@code AuthorIdNotIn} wycina autorów zablokowanych w którymkolwiek kierunku.
	 * Filtr jest w zapytaniu, a nie po stronie Javy, bo odsianie wierszy po
	 * pobraniu strony psułoby paginację: strona o rozmiarze 20 potrafiłaby wrócić z
	 * 15 wynikami, a {@code totalElements} liczyłby też ukryte posty.
	 */
	Page<Post> findByCommunityIdIsNullAndAuthorIdNotInOrderByCreatedAtDesc(Collection<UUID> excludedAuthorIds,
			Pageable pageable);

	Page<Post> findByAuthorIdAndCommunityIdIsNullOrderByCreatedAtDesc(UUID authorId, Pageable pageable);

	Page<Post> findByCommunityIdAndAuthorIdNotInOrderByCreatedAtDesc(UUID communityId,
			Collection<UUID> excludedAuthorIds, Pageable pageable);

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
	 *
	 * <p>
	 * {@code excludedAuthorIds} jest osobnym warunkiem od {@code userIds}, bo
	 * blokada nie usuwa posta z feedu „sama z siebie": zerwanie znajomości wyklucza
	 * zablokowanego z {@code userIds} tylko jako autora, ale jego post nadal
	 * wchodzi do feedu <b>udostępniony przez wspólnego znajomego</b>. To jedyna
	 * droga, którą treść zablokowanego wracała do oczu blokującego.
	 */
	@Query(value = "select p from Post p where p.communityId is null and p.authorId not in :excludedAuthorIds "
			+ "and (p.authorId in :userIds "
			+ "or exists (select 1 from Share s where s.postId = p.id and s.userId in :userIds)) "
			+ "order by p.createdAt desc", countQuery = "select count(p) from Post p where p.communityId is null "
					+ "and p.authorId not in :excludedAuthorIds and (p.authorId in :userIds "
					+ "or exists (select 1 from Share s where s.postId = p.id and s.userId in :userIds))")
	Page<Post> findFeedPosts(@Param("userIds") Collection<UUID> userIds,
			@Param("excludedAuthorIds") Collection<UUID> excludedAuthorIds, Pageable pageable);
}
