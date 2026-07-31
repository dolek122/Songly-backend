package com.songly.community;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CommunityBanRepository extends JpaRepository<CommunityBan, CommunityBanId> {

	boolean existsByCommunityIdAndUserId(UUID communityId, UUID userId);

	Page<CommunityBan> findByCommunityIdOrderByCreatedAtDesc(UUID communityId, Pageable pageable);

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
