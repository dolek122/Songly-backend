package com.songly.notification;

import com.songly.community.CommunityMemberJoinedEvent;
import com.songly.content.PostCommentedEvent;
import com.songly.content.PostLikedEvent;
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

	NotificationEventListener(NotificationService notificationService) {
		this.notificationService = notificationService;
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
}
