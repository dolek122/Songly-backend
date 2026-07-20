package com.songly.notification;

import com.songly.notification.dto.NotificationResponse;
import com.songly.profile.ProfileDirectory;
import java.time.Instant;
import java.util.Map;
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

	NotificationService(NotificationRepository notificationRepository, ProfileDirectory profileDirectory) {
		this.notificationRepository = notificationRepository;
		this.profileDirectory = profileDirectory;
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

	@Transactional(readOnly = true)
	Page<NotificationResponse> list(UUID userId, Pageable pageable) {
		Page<Notification> page = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
		Map<UUID, String> names = profileDirectory
				.displayNames(page.getContent().stream().map(Notification::getActorId).collect(Collectors.toSet()));
		return page.map(notification -> new NotificationResponse(notification.getId(), notification.getType(),
				notification.getActorId(), names.get(notification.getActorId()), notification.getResourceId(),
				notification.getCreatedAt(), notification.getReadAt()));
	}

	@Transactional(readOnly = true)
	long unreadCount(UUID userId) {
		return notificationRepository.countByUserIdAndReadAtIsNull(userId);
	}

	/** Cudze powiadomienia zgłaszamy jako nieistniejące. */
	@Transactional
	void markRead(UUID notificationId, UUID userId) {
		Notification notification = notificationRepository.findById(notificationId)
				.filter(n -> n.getUserId().equals(userId)).orElseThrow(NotificationNotFoundException::new);
		notification.markRead(Instant.now());
	}

	@Transactional
	void markAllRead(UUID userId) {
		Instant now = Instant.now();
		notificationRepository.findByUserIdAndReadAtIsNull(userId).forEach(notification -> notification.markRead(now));
	}
}
