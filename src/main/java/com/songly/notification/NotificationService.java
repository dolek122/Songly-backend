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
		eventPublisher.publishEvent(new NotificationCreatedEvent(id, recipientId, actorId, type, resourceId));
	}

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
			boolean anonymous = NotificationType.DECIDED_BY_MODERATION.contains(notification.getType());
			UUID actorId = notification.getActorId();
			return new NotificationResponse(notification.getId(), notification.getType(), actorId,
					anonymous ? null : names.get(actorId), anonymous ? null : avatars.get(actorId),
					notification.getResourceId(), notification.getCreatedAt(), notification.getReadAt());
		});
	}

	@Transactional(readOnly = true)
	long unreadCount(UUID userId) {
		return notificationRepository.countVisibleUnread(userId, socialGraph.blockedEitherWayIds(userId),
				NotificationType.MODERATION);
	}

	@Transactional
	void markRead(UUID notificationId, UUID userId) {
		Notification notification = notificationRepository.findById(notificationId)
				.filter(n -> n.getUserId().equals(userId)).orElseThrow(NotificationNotFoundException::new);
		notification.markRead(Instant.now());
	}

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
