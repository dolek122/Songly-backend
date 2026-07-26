package com.songly.community;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface CommunityRepository extends JpaRepository<Community, UUID> {

	Page<Community> findAllByOrderByNameAsc(Pageable pageable);

	boolean existsByNameIgnoreCase(String name);

	/**
	 * Encje, nie masowy {@code deleteByOwnerId}: kasowanie każdej osobno pozwala
	 * opublikować {@link CommunityDeletedEvent}, bez którego posty w tych
	 * społecznościach zostałyby z wiszącym kluczem obcym.
	 */
	List<Community> findByOwnerId(UUID ownerId);
}
