package com.songly.content;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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
	 * Czy post czeka już w kolejce, czyli ma zgłoszenie nierozpatrzone — patrz
	 * {@code PostReportedEvent}. Zgłoszenia odrzucone się nie liczą: po ich
	 * odrzuceniu kolejka jest dla tego posta pusta, więc nowe zgłoszenie znów musi
	 * kogoś zawołać.
	 */
	boolean existsByPostIdAndStatus(UUID postId, ReportStatus status);

	/** Ile zgłoszeń ma post — liczone przed jego usunięciem (ADR 0024). */
	long countByPostId(UUID postId);

	/**
	 * Kto zgłosił ten post — czytane PRZED jego usunięciem (ADR 0027), bo kaskada
	 * zabiera zgłoszenia razem z postem, a to właśnie usunięcie posta jest chwilą,
	 * w której zgłoszenie okazuje się trafne.
	 */
	@Query("select r.reporterId from PostReport r where r.postId = :postId")
	List<UUID> findReporterIdsByPostId(@Param("postId") UUID postId);

	/**
	 * Zgłoszenia jednej osoby w oknie przesuwanym — limit z ADR 0018. Liczymy
	 * wiersze zamiast trzymać licznik w Redisie, bo {@code created_at} i tak tu
	 * jest, a limit ma być warunkiem domeny, nie zabezpieczeniem infrastruktury.
	 */
	long countByReporterIdAndCreatedAtAfter(UUID reporterId, Instant createdAfter);

	/**
	 * Ile zgłoszeń tej osoby moderacja odrzuciła — <b>w całej aplikacji</b>, bez
	 * podziału na społeczności (ADR 0025). Zasięg globalny jest tu konieczny:
	 * zasypywanie rozłożone na dziesięć społeczności wymyka się każdemu licznikowi
	 * liczonemu per społeczność. Ta liczba nigdzie nie wypływa — służy wyłącznie do
	 * policzenia limitu, czyli do odpowiedzi „nie" wobec samego zgłaszającego.
	 *
	 * <p>
	 * Liczymy po tabeli zgłoszeń, nie po historii moderacji (ADR 0024): dziennik
	 * jest w module, który konsumuje zdarzenia modułu content, więc sięgnięcie po
	 * niego stąd zrobiłoby cykl między modułami (reguła 2
	 * {@code ModuleBoundariesTest}). Koszt: odrzucenia, których post usunięto
	 * później, znikają razem z nim — licznik jest więc zaniżony, nigdy zawyżony, i
	 * to jest właściwa strona pomyłki.
	 */
	long countByReporterIdAndStatus(UUID reporterId, ReportStatus status);

	/**
	 * Odrzucone zgłoszenia podanych osób <b>w jednej społeczności</b> — do
	 * pokazania w kolejce. Zasięg jest tu celowo węższy niż przy limicie: moderator
	 * społeczności nie ma prawa dowiedzieć się, co ten człowiek robił w innych
	 * społecznościach (Claude.md §8 — moderacja jest społecznościowa, nie
	 * globalna).
	 *
	 * <p>
	 * Jedno zapytanie na stronę kolejki, nie jedno na zgłoszenie.
	 */
	@Query("""
			select r.reporterId as reporterId, count(r) as dismissedCount from PostReport r
			join Post p on p.id = r.postId
			where p.communityId = :communityId and r.status = :status and r.reporterId in :reporterIds
			group by r.reporterId
			""")
	List<ReporterDismissedCount> countDismissedByReporterIdIn(@Param("communityId") UUID communityId,
			@Param("reporterIds") Collection<UUID> reporterIds, @Param("status") ReportStatus status);

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

	interface ReporterDismissedCount {

		UUID getReporterId();

		long getDismissedCount();
	}
}
