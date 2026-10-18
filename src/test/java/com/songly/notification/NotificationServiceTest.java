package com.songly.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.songly.profile.ProfileDirectory;
import com.songly.social.SocialGraph;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

	@Mock
	private NotificationRepository notificationRepository;

	@Mock
	private ProfileDirectory profileDirectory;

	@Mock
	private SocialGraph socialGraph;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private NotificationService notificationService;

	private final UUID recipientId = UUID.randomUUID();
	private final UUID actorId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		notificationService = new NotificationService(notificationRepository, profileDirectory, socialGraph,
				eventPublisher);
	}

	@Test
	void record_savesNotification() {
		notificationService.record(recipientId, actorId, NotificationType.POST_LIKED, UUID.randomUUID(), Instant.now());

		verify(notificationRepository).save(any(Notification.class));
	}

	@Test
	void record_skipsOwnActivity() {
		notificationService.record(recipientId, recipientId, NotificationType.POST_LIKED, UUID.randomUUID(),
				Instant.now());

		verify(notificationRepository, never()).save(any());
	}

	/** Pętla unlike/like (ADR 0035): przywrócony stan to nie nowa wiadomość. */
	@Test
	void record_skipsRepeatedStateNotification_forSameActorAndResource() {
		UUID postId = UUID.randomUUID();
		when(notificationRepository.existsByUserIdAndTypeAndActorIdAndResourceId(recipientId,
				NotificationType.POST_LIKED, actorId, postId)).thenReturn(true);

		notificationService.record(recipientId, actorId, NotificationType.POST_LIKED, postId, Instant.now());

		verify(notificationRepository, never()).save(any());
	}

	/** Każdy komentarz to nowa treść — nie podlega deduplikacji. */
	@Test
	void record_neverDeduplicatesComments() {
		notificationService.record(recipientId, actorId, NotificationType.POST_COMMENTED, UUID.randomUUID(),
				Instant.now());

		verify(notificationRepository, never()).existsByUserIdAndTypeAndActorIdAndResourceId(any(), any(), any(),
				any());
		verify(notificationRepository).save(any(Notification.class));
	}

	@Test
	void record_collapsesFriendRequestIntoUnreadOne_pointingAtNewestRequest() {
		Notification unread = new Notification(UUID.randomUUID(), recipientId, NotificationType.FRIEND_REQUEST_RECEIVED,
				actorId, UUID.randomUUID(), Instant.parse("2026-01-01T00:00:00Z"));
		when(notificationRepository.findFirstByUserIdAndTypeAndActorIdAndReadAtIsNull(recipientId,
				NotificationType.FRIEND_REQUEST_RECEIVED, actorId)).thenReturn(Optional.of(unread));
		UUID newRequestId = UUID.randomUUID();
		Instant now = Instant.parse("2026-02-01T00:00:00Z");

		notificationService.record(recipientId, actorId, NotificationType.FRIEND_REQUEST_RECEIVED, newRequestId, now);

		verify(notificationRepository, never()).save(any());
		assertThat(unread.getResourceId()).isEqualTo(newRequestId);
		assertThat(unread.getCreatedAt()).isEqualTo(now);
	}

	@Test
	void record_savesNewFriendRequestNotification_whenPreviousWasRead() {
		when(notificationRepository.findFirstByUserIdAndTypeAndActorIdAndReadAtIsNull(recipientId,
				NotificationType.FRIEND_REQUEST_RECEIVED, actorId)).thenReturn(Optional.empty());

		notificationService.record(recipientId, actorId, NotificationType.FRIEND_REQUEST_RECEIVED, UUID.randomUUID(),
				Instant.now());

		verify(notificationRepository).save(any(Notification.class));
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
		Notification second = new Notification(UUID.randomUUID(), recipientId, NotificationType.POST_COMMENTED, actorId,
				UUID.randomUUID(), Instant.now());
		when(socialGraph.blockedEitherWayIds(recipientId)).thenReturn(Set.of());
		when(notificationRepository.findVisibleUnread(recipientId, Set.of(), NotificationType.MODERATION))
				.thenReturn(List.of(first, second));

		notificationService.markAllRead(recipientId);

		assertThat(first.getReadAt()).isNotNull();
		assertThat(second.getReadAt()).isNotNull();
	}

	/**
	 * „Oznacz wszystkie" nie może sięgać do ukrytych — inaczej po odblokowaniu
	 * wracałyby przeczytane, mimo że nikt ich nigdy nie zobaczył.
	 */
	@Test
	void markAllRead_leavesNotificationsFromBlockedUsersUntouched() {
		UUID blockedId = UUID.randomUUID();
		when(socialGraph.blockedEitherWayIds(recipientId)).thenReturn(Set.of(blockedId));
		when(notificationRepository.findVisibleUnread(recipientId, Set.of(blockedId), NotificationType.MODERATION))
				.thenReturn(List.of());

		notificationService.markAllRead(recipientId);

		verify(notificationRepository).findVisibleUnread(recipientId, Set.of(blockedId), NotificationType.MODERATION);
	}

	@Test
	void unreadCount_excludesBlockedActors() {
		UUID blockedId = UUID.randomUUID();
		when(socialGraph.blockedEitherWayIds(recipientId)).thenReturn(Set.of(blockedId));
		when(notificationRepository.countVisibleUnread(recipientId, Set.of(blockedId), NotificationType.MODERATION))
				.thenReturn(2L);

		assertThat(notificationService.unreadCount(recipientId)).isEqualTo(2L);
	}
}
