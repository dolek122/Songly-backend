package com.songly.community;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CommunityBanRepository extends JpaRepository<CommunityBan, CommunityBanId> {

	/**
	 * Czy ban <b>aktywny</b> — wygasły nie liczy się nigdzie (ADR 0014). Wygasanie
	 * jest leniwe: filtr w zapytaniu jest jedynym mechanizmem, żaden proces nie
	 * sprząta w chwili wygaśnięcia. Warunek jest alternatywą ({@code null} =
	 * bezterminowy), więc nazwa metody Spring Data go nie wyrazi — stąd
	 * {@code @Query}, jak przy filtrze blokad w notification.
	 */
	@Query("""
			select count(b) > 0 from CommunityBan b
			where b.communityId = :communityId and b.userId = :userId
			and (b.expiresAt is null or b.expiresAt > :now)
			""")
	boolean existsActive(@Param("communityId") UUID communityId, @Param("userId") UUID userId,
			@Param("now") Instant now);

	/**
	 * Lista dla moderatorów pokazuje wyłącznie bany aktywne — wygasły to historia.
	 */
	@Query("""
			select b from CommunityBan b
			where b.communityId = :communityId and (b.expiresAt is null or b.expiresAt > :now)
			order by b.createdAt desc
			""")
	Page<CommunityBan> findActiveByCommunityId(@Param("communityId") UUID communityId, @Param("now") Instant now,
			Pageable pageable);

	/**
	 * Sprawy czekające na drugą instancję (ADR 0028) — najstarsze pierwsze, bo
	 * kolejka administracji ma być kolejką, nie stosem.
	 */
	List<CommunityBan> findByAppealStatusOrderByAppealEscalatedAtAsc(BanAppealStatus appealStatus);

	/**
	 * Wsadowo, i to jest warunek poprawności, nie optymalizacja. Przy usuwaniu
	 * konta te same wiersze potrafi zabrać kaskada z innej strony: skasowanie
	 * społeczności, której usuwany user jest właścicielem, usuwa jej bany. Wariant
	 * wyprowadzony wczytuje encje i kasuje je dopiero przy flushu — czyli już po
	 * kaskadzie, a Hibernate zgłasza wtedy „expected row count 1 but was 0" i całe
	 * usunięcie konta kończy się 500-tką. Na banach nie wisi żaden cykl życia, więc
	 * ostrzeżenie z Claude.md §8 tu nie obowiązuje.
	 */
	@Modifying(flushAutomatically = true)
	@Query("delete from CommunityBan b where b.userId = :userId")
	void deleteByUserId(@Param("userId") UUID userId);

	/**
	 * Bany nałożone przez znikające konto <b>zostają</b>, tracą tylko autora —
	 * należą do społeczności, nie do osoby, która je nałożyła. Gdyby znikały razem
	 * z jej kontem, odejście moderatora po cichu wpuszczałoby z powrotem
	 * wszystkich, których zbanował. Wsadowo z tego samego powodu co wyżej.
	 */
	@Modifying(flushAutomatically = true)
	@Query("update CommunityBan b set b.bannedBy = null where b.bannedBy = :bannedBy")
	void clearBannedBy(@Param("bannedBy") UUID bannedBy);
}
