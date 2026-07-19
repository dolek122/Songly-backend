package com.songly.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.songly.profile.ProfileDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

	@Mock
	private NotificationRepository notificationRepository;

	@Mock
	private ProfileDirectory profileDirectory;

	private NotificationService notificationService;

	private final UUID recipientId = UUID.randomUUID();
	private final UUID actorId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		notificationService = new NotificationService(notificationRepository, profileDirectory);
	}

	@Test
	void record_savesNotification() {
		notificationService.record(recipientId, actorId, NotificationType.POST_LIKED, UUID.randomUUID(),
				Instant.now());

		verify(notificationRepository).save(any(Notification.class));
	}

	@Test
	void record_skipsOwnActivity() {
		notificationService.record(recipientId, recipientId, NotificationType.POST_LIKED, UUID.randomUUID(),
				Instant.now());

		verify(notificationRepository, never()).save(any());
	}

	@Test
	void markRead_setsReadAt() {
		Notification notification = new Notification(UUID.randomUUID(), recipientId, NotificationType.POST_LIKED,
				actorId, UUID.randomUUID(), Instant.now());
		when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));

		notificationService.markRead(notification.getId(), recipientId);

		assertThat(notification.getReadAt()).isNotNull();
	}

	@Test
	void markRead_reportsNotFound_whenNotificationBelongsToSomeoneElse() {
		Notification notification = new Notification(UUID.randomUUID(), recipientId, NotificationType.POST_LIKED,
				actorId, UUID.randomUUID(), Instant.now());
		when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));

		assertThatThrownBy(() -> notificationService.markRead(notification.getId(), actorId))
				.isInstanceOf(NotificationNotFoundException.class);
	}

	@Test
	void markRead_doesNotOverwriteExistingReadAt() {
		Notification notification = new Notification(UUID.randomUUID(), recipientId, NotificationType.POST_LIKED,
				actorId, UUID.randomUUID(), Instant.now());
		notification.markRead(Instant.parse("2026-01-01T00:00:00Z"));
		when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));

		notificationService.markRead(notification.getId(), recipientId);

		assertThat(notification.getReadAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
	}

	@Test
	void markAllRead_marksEveryUnread() {
		Notification first = new Notification(UUID.randomUUID(), recipientId, NotificationType.POST_LIKED, actorId,
				UUID.randomUUID(), Instant.now());
		Notification second = new Notification(UUID.randomUUID(), recipientId, NotificationType.POST_COMMENTED,
				actorId, UUID.randomUUID(), Instant.now());
		when(notificationRepository.findByUserIdAndReadAtIsNull(recipientId)).thenReturn(List.of(first, second));

		notificationService.markAllRead(recipientId);

		assertThat(first.getReadAt()).isNotNull();
		assertThat(second.getReadAt()).isNotNull();
	}
}
