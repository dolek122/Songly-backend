package com.songly.notification;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface NotificationRepository extends JpaRepository<Notification, UUID> {

	/**
	 * {@code ActorIdNotIn} ukrywa powiadomienia od osób zablokowanych w
	 * którymkolwiek kierunku. Filtr jest w zapytaniu, nie po pobraniu strony — po
	 * fakcie psułby paginację, tak samo jak w module content.
	 */
	Page<Notification> findByUserIdAndActorIdNotInOrderByCreatedAtDesc(UUID userId, Collection<UUID> excludedActorIds,
			Pageable pageable);

	List<Notification> findByUserIdAndReadAtIsNullAndActorIdNotIn(UUID userId, Collection<UUID> excludedActorIds);

	long countByUserIdAndReadAtIsNullAndActorIdNotIn(UUID userId, Collection<UUID> excludedActorIds);

	void deleteByUserIdOrActorId(UUID userId, UUID actorId);
}
