package com.songly.notification;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface NotificationRepository extends JpaRepository<Notification, UUID> {

	/**
	 * Ukrywa powiadomienia od osób zablokowanych w którymkolwiek kierunku —
	 * <b>poza</b> powiadomieniami o decyzjach moderacyjnych, które przechodzą
	 * zawsze (patrz {@link NotificationType#MODERATION}).
	 *
	 * <p>
	 * Filtr jest w zapytaniu, nie po pobraniu strony: po fakcie psułby paginację,
	 * tak samo jak w module content. Pusta lista zablokowanych to przypadek
	 * większości użytkowników i działa poprawnie — {@code not in ()} jest prawdą
	 * dla każdego wiersza.
	 */
	@Query("""
			select n from Notification n
			where n.userId = :userId and (n.actorId not in :excludedActorIds or n.type in :alwaysVisibleTypes)
			order by n.createdAt desc
			""")
	Page<Notification> findVisible(@Param("userId") UUID userId,
			@Param("excludedActorIds") Collection<UUID> excludedActorIds,
			@Param("alwaysVisibleTypes") Collection<NotificationType> alwaysVisibleTypes, Pageable pageable);

	@Query("""
			select n from Notification n
			where n.userId = :userId and n.readAt is null
			and (n.actorId not in :excludedActorIds or n.type in :alwaysVisibleTypes)
			""")
	List<Notification> findVisibleUnread(@Param("userId") UUID userId,
			@Param("excludedActorIds") Collection<UUID> excludedActorIds,
			@Param("alwaysVisibleTypes") Collection<NotificationType> alwaysVisibleTypes);

	@Query("""
			select count(n) from Notification n
			where n.userId = :userId and n.readAt is null
			and (n.actorId not in :excludedActorIds or n.type in :alwaysVisibleTypes)
			""")
	long countVisibleUnread(@Param("userId") UUID userId, @Param("excludedActorIds") Collection<UUID> excludedActorIds,
			@Param("alwaysVisibleTypes") Collection<NotificationType> alwaysVisibleTypes);

	boolean existsByUserIdAndTypeAndActorIdAndResourceId(UUID userId, NotificationType type, UUID actorId,
			UUID resourceId);

	Optional<Notification> findFirstByUserIdAndTypeAndActorIdAndReadAtIsNull(UUID userId, NotificationType type,
			UUID actorId);

	void deleteByUserIdOrActorId(UUID userId, UUID actorId);
}
