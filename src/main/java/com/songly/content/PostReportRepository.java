package com.songly.content;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PostReportRepository extends JpaRepository<PostReport, UUID> {

	boolean existsByPostIdAndReporterId(UUID postId, UUID reporterId);

	/**
	 * Czy post ma już jakiekolwiek zgłoszenie — patrz {@code PostReportedEvent}.
	 */
	boolean existsByPostId(UUID postId);

	/**
	 * Zgłoszenia jednej osoby w oknie przesuwanym — limit z ADR 0018. Liczymy
	 * wiersze zamiast trzymać licznik w Redisie, bo {@code created_at} i tak tu
	 * jest, a limit ma być warunkiem domeny, nie zabezpieczeniem infrastruktury.
	 */
	long countByReporterIdAndCreatedAtAfter(UUID reporterId, Instant createdAfter);

	/**
	 * Kolejka jednej społeczności. Złączenie z {@code Post} jest tu konieczne, bo
	 * to post — nie zgłoszenie — wie, do której społeczności należy. Duplikowanie
	 * {@code communityId} na zgłoszeniu dałoby drugie źródło prawdy, które
	 * rozjechałoby się przy pierwszym przeniesieniu posta.
	 */
	@Query("""
			select r from PostReport r join Post p on p.id = r.postId
			where p.communityId = :communityId and r.status = :status
			order by r.createdAt asc
			""")
	Page<PostReport> findByCommunityIdAndStatus(@Param("communityId") UUID communityId,
			@Param("status") ReportStatus status, Pageable pageable);

	/** Zliczenia dla całej strony zgłoszeń jednym zapytaniem (bez N+1). */
	@Query("""
			select r.postId as postId, count(r) as reportCount from PostReport r
			where r.postId in :postIds and r.status = :status group by r.postId
			""")
	java.util.List<PostIdCount> countByPostIdIn(@Param("postIds") java.util.Collection<UUID> postIds,
			@Param("status") ReportStatus status);

	/**
	 * Kasowanie <b>wsadowe</b>, i to jest warunek poprawności, nie optymalizacja.
	 *
	 * <p>
	 * Przy usuwaniu konta te same wiersze potrafi zabrać kaskada z zupełnie innej
	 * strony: gdy usuwany user jest właścicielem społeczności, jej skasowanie usuwa
	 * posty, a te kaskadowo swoje zgłoszenia. Wariant wyprowadzony
	 * ({@code deleteByReporterId} bez zapytania) wczytuje encje i kasuje je dopiero
	 * przy flushu — czyli po kaskadzie, gdy wierszy już nie ma. Hibernate zgłasza
	 * wtedy „expected row count 1 but was 0" i całe usunięcie konta kończy się
	 * 500-tką. Zapytanie wsadowe idzie do bazy od razu i jest odporne na kolejność.
	 *
	 * <p>
	 * Ostrzeżenie z Claude.md §8 o kasowaniu wsadowym nie stosuje się tutaj:
	 * dotyczy encji, na których cyklu życia wiszą zdarzenia. Na zgłoszeniach nie
	 * wisi nic.
	 */
	@Modifying(flushAutomatically = true)
	@Query("delete from PostReport r where r.reporterId = :reporterId")
	void deleteByReporterId(@Param("reporterId") UUID reporterId);

	/**
	 * Zgłoszenia rozpatrzone przez znikające konto tracą autora decyzji, ale
	 * zostają — odejście moderatora nie może cofać rozstrzygnięć, które podjął.
	 * Wsadowo z tego samego powodu co wyżej.
	 */
	@Modifying(flushAutomatically = true)
	@Query("update PostReport r set r.reviewedBy = null where r.reviewedBy = :reviewerId")
	void clearReviewer(@Param("reviewerId") UUID reviewerId);

	interface PostIdCount {

		UUID getPostId();

		long getReportCount();
	}
}
