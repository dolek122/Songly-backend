package com.songly.notification;

import com.songly.community.CommunityMemberBannedEvent;
import com.songly.community.CommunityMemberJoinedEvent;
import com.songly.community.CommunityMembership;
import com.songly.content.CommunityPostRemovedEvent;
import com.songly.content.PostCommentedEvent;
import com.songly.content.PostLikedEvent;
import com.songly.content.PostReportDismissedEvent;
import com.songly.content.PostReportedEvent;
import com.songly.content.PostSharedEvent;
import com.songly.social.FriendRequestAcceptedEvent;
import com.songly.social.FriendRequestSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Jedyny kontakt modułu notification z resztą systemu: zdarzenia Springa
 * publikowane przez content, social i community (Claude.md §2) — bez sięgania
 * po ich encje ani serwisy.
 */
@Component
class NotificationEventListener {

	private final NotificationService notificationService;
	private final CommunityMembership communityMembership;

	NotificationEventListener(NotificationService notificationService, CommunityMembership communityMembership) {
		this.notificationService = notificationService;
		this.communityMembership = communityMembership;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostLiked(PostLikedEvent event) {
		notificationService.record(event.postAuthorId(), event.likerId(), NotificationType.POST_LIKED, event.postId(),
				event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostCommented(PostCommentedEvent event) {
		notificationService.record(event.postAuthorId(), event.commentAuthorId(), NotificationType.POST_COMMENTED,
				event.postId(), event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostShared(PostSharedEvent event) {
		notificationService.record(event.postAuthorId(), event.sharerId(), NotificationType.POST_SHARED, event.postId(),
				event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onFriendRequestSent(FriendRequestSentEvent event) {
		notificationService.record(event.addresseeId(), event.requesterId(), NotificationType.FRIEND_REQUEST_RECEIVED,
				event.requestId(), event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onFriendRequestAccepted(FriendRequestAcceptedEvent event) {
		notificationService.record(event.requesterId(), event.addresseeId(), NotificationType.FRIEND_REQUEST_ACCEPTED,
				event.requestId(), event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommunityMemberJoined(CommunityMemberJoinedEvent event) {
		notificationService.record(event.ownerId(), event.memberId(), NotificationType.COMMUNITY_MEMBER_JOINED,
				event.communityId(), event.occurredAt());
	}

	/**
	 * Bez tego zbanowany dowiadywał się o banie dopiero przy próbie napisania
	 * czegokolwiek — czyli decyzja moderacyjna wyglądała jak awaria aplikacji.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommunityMemberBanned(CommunityMemberBannedEvent event) {
		notificationService.record(event.userId(), event.moderatorId(), NotificationType.COMMUNITY_BANNED,
				event.communityId(), event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommunityPostRemoved(CommunityPostRemovedEvent event) {
		notificationService.record(event.postAuthorId(), event.moderatorId(), NotificationType.COMMUNITY_POST_REMOVED,
				event.communityId(), event.occurredAt());
	}

	/**
	 * Do wszystkich moderatorów naraz — to jest ta połowa moderacji, której kolejka
	 * sama nie załatwia. Kolejka wymaga, żeby ktoś do niej zajrzał; powiadomienie
	 * samo mówi, że jest po co.
	 *
	 * <p>
	 * Adresatów pyta moduł community przez swój publiczny interfejs
	 * ({@code CommunityMembership.moderatorIds}) — notification nie ma prawa
	 * zaglądać do tabeli członkostw (Claude.md §2). Zgłaszający, jeśli sam jest
	 * moderatorem, odpada w {@code record}: nikt nie zawiadamia siebie.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostReported(PostReportedEvent event) {
		communityMembership.moderatorIds(event.communityId())
				.forEach(moderatorId -> notificationService.record(moderatorId, event.reporterId(),
						NotificationType.COMMUNITY_POST_REPORTED, event.postId(), event.occurredAt()));
	}

	/**
	 * Bez informacji zwrotnej zgłaszanie jest wrzucaniem kartek do zamkniętej
	 * skrzynki — po kilku takich razach przestaje się zgłaszać cokolwiek.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostReportDismissed(PostReportDismissedEvent event) {
		notificationService.record(event.reporterId(), event.moderatorId(), NotificationType.POST_REPORT_DISMISSED,
				event.postId(), event.occurredAt());
	}
}
