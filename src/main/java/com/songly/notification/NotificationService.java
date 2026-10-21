package com.songly.notification;

import com.songly.notification.dto.NotificationResponse;
import com.songly.profile.ProfileDirectory;
import com.songly.social.SocialGraph;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class NotificationService {

	private final NotificationRepository notificationRepository;
	private final ProfileDirectory profileDirectory;
	private final SocialGraph socialGraph;
	private final ApplicationEventPublisher eventPublisher;

	NotificationService(NotificationRepository notificationRepository, ProfileDirectory profileDirectory,
			SocialGraph socialGraph, ApplicationEventPublisher eventPublisher) {
		this.notificationRepository = notificationRepository;
		this.profileDirectory = profileDirectory;
		this.socialGraph = socialGraph;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * REQUIRES_NEW celowo: wywoływane z {@code AFTER_COMMIT} listenerów, gdzie
	 * transakcja źródłowa już się zakończyła (ten sam wzorzec co
	 * {@code ProfileService.createDefaultProfile}). Własna aktywność (np. lajk
	 * własnego posta) nie generuje powiadomienia, a cofnięty i przywrócony stan nie
	 * generuje drugiego (ADR 0035).
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void record(UUID recipientId, UUID actorId, NotificationType type, UUID resourceId, Instant occurredAt) {
		if (recipientId.equals(actorId)) {
			return;
		}
		if (NotificationType.ONCE_PER_RESOURCE.contains(type) && notificationRepository
				.existsByUserIdAndTypeAndActorIdAndResourceId(recipientId, type, actorId, resourceId)) {
			return;
		}
		if (NotificationType.COLLAPSED_WHILE_UNREAD.contains(type)) {
			Optional<Notification> unread = notificationRepository
					.findFirstByUserIdAndTypeAndActorIdAndReadAtIsNull(recipientId, type, actorId);
			if (unread.isPresent()) {
				unread.get().refresh(resourceId, occurredAt);
				return;
			}
		}
		UUID id = UUID.randomUUID();
		notificationRepository.save(new Notification(id, recipientId, type, actorId, resourceId, occurredAt));
		// Push dopiero po commicie tej (REQUIRES_NEW) transakcji — powiadomienie,
		// które się nie zapisało, nie może zadzwonić komuś w kieszeni (ADR 0040).
		eventPublisher.publishEvent(new NotificationCreatedEvent(id, recipientId, actorId, type, resourceId));
	}

	/**
	 * Powiadomienia od zablokowanych <b>ukrywamy przy odczycie, a nie kasujemy przy
	 * blokadzie</b>. Kasowanie byłoby nieodwracalne: po odblokowaniu skrzynka
	 * wracałaby z dziurą, a blokada bywa tymczasowa i zdejmowana. Przy filtrze
	 * odczytu odblokowanie po prostu przywraca to, co było — łącznie ze stanem
	 * przeczytania.
	 */
	@Transactional(readOnly = true)
	Page<NotificationResponse> list(UUID userId, Pageable pageable) {
		Page<Notification> page = notificationRepository.findVisible(userId, socialGraph.blockedEitherWayIds(userId),
				NotificationType.MODERATION, pageable);
		Set<UUID> actorIds = page.getContent().stream()
				.filter(notification -> !NotificationType.DECIDED_BY_MODERATION.contains(notification.getType()))
				.map(Notification::getActorId).collect(Collectors.toSet());
		Map<UUID, String> names = profileDirectory.displayNames(actorIds);
		Map<UUID, String> avatars = profileDirectory.avatarUrls(actorIds);
		return page.map(notification -> {
			// Przy decyzjach moderacji bez nazwy i awatara (ADR 0015) — patrz
			// NotificationType.DECIDED_BY_MODERATION. Sprawdzane per wiersz, nie tylko
			// przy budowaniu map: ten sam moderator może mieć na tej samej stronie
			// zwykłe powiadomienie (np. lajk), a wtedy jego nazwa jest w mapie.
			boolean anonymous = NotificationType.DECIDED_BY_MODERATION.contains(notification.getType());
			UUID actorId = notification.getActorId();
			return new NotificationResponse(notification.getId(), notification.getType(), actorId,
					anonymous ? null : names.get(actorId), anonymous ? null : avatars.get(actorId),
					notification.getResourceId(), notification.getCreatedAt(), notification.getReadAt());
		});
	}

	/**
	 * Licznik musi filtrować tak samo jak lista, inaczej plakietka pokazywałaby „3
	 * nowe" nad skrzynką, w której nie widać żadnego nowego powiadomienia — i nie
	 * dałoby się jej zbić.
	 */
	@Transactional(readOnly = true)
	long unreadCount(UUID userId) {
		return notificationRepository.countVisibleUnread(userId, socialGraph.blockedEitherWayIds(userId),
				NotificationType.MODERATION);
	}

	/** Cudze powiadomienia zgłaszamy jako nieistniejące. */
	@Transactional
	void markRead(UUID notificationId, UUID userId) {
		Notification notification = notificationRepository.findById(notificationId)
				.filter(n -> n.getUserId().equals(userId)).orElseThrow(NotificationNotFoundException::new);
		notification.markRead(Instant.now());
	}

	/**
	 * Oznacza jako przeczytane wyłącznie to, co użytkownik faktycznie widzi. Gdyby
	 * obejmowało też ukryte powiadomienia, po odblokowaniu wróciłyby przeczytane,
	 * mimo że nikt ich nigdy nie zobaczył — a filtr blokady ma być warstwą widoku,
	 * nie cichą zmianą stanu.
	 */
	@Transactional
	void markAllRead(UUID userId) {
		Instant now = Instant.now();
		notificationRepository
				.findVisibleUnread(userId, socialGraph.blockedEitherWayIds(userId), NotificationType.MODERATION)
				.forEach(notification -> notification.markRead(now));
	}

	@Transactional
	void deleteAccountData(UUID userId) {
		notificationRepository.deleteByUserIdOrActorId(userId, userId);
	}
}
