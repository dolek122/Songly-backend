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

	/**
	 * {@code AuthorIdNotIn} wycina komentarze osób zablokowanych w którymkolwiek
	 * kierunku — pod cudzym postem blokujący i zablokowany spotykają się inaczej
	 * niż w feedzie, więc bez tego blokada nie zasłaniałaby najbardziej
	 * bezpośredniego kanału kontaktu, jaki mamy.
	 */
	Page<Comment> findByPostIdAndAuthorIdNotInOrderByCreatedAtAsc(UUID postId, Collection<UUID> excludedAuthorIds,
			Pageable pageable);

	long countByPostId(UUID postId);

	void deleteByAuthorId(UUID authorId);

	/**
	 * Zliczenia dla całej strony postów jednym zapytaniem (bez N+1).
	 *
	 * <p>
	 * Wyklucza tych samych autorów co lista komentarzy — inaczej post pokazywałby
	 * „5 komentarzy" nad listą czterech. Lajków i udostępnień celowo <b>nie</b>
	 * filtrujemy: to czyste liczby, bez tożsamości, więc nie da się z nich
	 * odczytać, że zablokowany tu był, ani niczego mu odpisać.
	 */
	@Query("select c.postId as postId, count(c) as commentCount from Comment c where c.postId in :postIds "
			+ "and c.authorId not in :excludedAuthorIds group by c.postId")
	List<PostIdCount> countByPostIdIn(@Param("postIds") Collection<UUID> postIds,
			@Param("excludedAuthorIds") Collection<UUID> excludedAuthorIds);

	interface PostIdCount {

		UUID getPostId();

		long getCommentCount();
	}
}
