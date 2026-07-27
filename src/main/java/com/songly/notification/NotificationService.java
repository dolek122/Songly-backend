package com.songly.notification;

import com.songly.notification.dto.NotificationResponse;
import com.songly.profile.ProfileDirectory;
import com.songly.social.SocialGraph;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

	NotificationService(NotificationRepository notificationRepository, ProfileDirectory profileDirectory,
			SocialGraph socialGraph) {
		this.notificationRepository = notificationRepository;
		this.profileDirectory = profileDirectory;
		this.socialGraph = socialGraph;
	}

	/**
	 * REQUIRES_NEW celowo: wywoływane z {@code AFTER_COMMIT} listenerów, gdzie
	 * transakcja źródłowa już się zakończyła (ten sam wzorzec co
	 * {@code ProfileService.createDefaultProfile}). Własna aktywność (np. lajk
	 * własnego posta) nie generuje powiadomienia.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void record(UUID recipientId, UUID actorId, NotificationType type, UUID resourceId, Instant occurredAt) {
		if (recipientId.equals(actorId)) {
			return;
		}
		notificationRepository
				.save(new Notification(UUID.randomUUID(), recipientId, type, actorId, resourceId, occurredAt));
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
		Page<Notification> page = notificationRepository.findByUserIdAndActorIdNotInOrderByCreatedAtDesc(userId,
				socialGraph.blockedEitherWayIds(userId), pageable);
		Set<UUID> actorIds = page.getContent().stream().map(Notification::getActorId).collect(Collectors.toSet());
		Map<UUID, String> names = profileDirectory.displayNames(actorIds);
		Map<UUID, String> avatars = profileDirectory.avatarUrls(actorIds);
		return page.map(notification -> new NotificationResponse(notification.getId(), notification.getType(),
				notification.getActorId(), names.get(notification.getActorId()), avatars.get(notification.getActorId()),
				notification.getResourceId(), notification.getCreatedAt(), notification.getReadAt()));
	}

	/**
	 * Licznik musi filtrować tak samo jak lista, inaczej plakietka pokazywałaby „3
	 * nowe" nad skrzynką, w której nie widać żadnego nowego powiadomienia — i nie
	 * dałoby się jej zbić.
	 */
	@Transactional(readOnly = true)
	long unreadCount(UUID userId) {
		return notificationRepository.countByUserIdAndReadAtIsNullAndActorIdNotIn(userId,
				socialGraph.blockedEitherWayIds(userId));
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
				.findByUserIdAndReadAtIsNullAndActorIdNotIn(userId, socialGraph.blockedEitherWayIds(userId))
				.forEach(notification -> notification.markRead(now));
	}

	@Transactional
	void deleteAccountData(UUID userId) {
		notificationRepository.deleteByUserIdOrActorId(userId, userId);
	}
}
