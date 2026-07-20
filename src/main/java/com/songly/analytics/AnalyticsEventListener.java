package com.songly.analytics;

import com.songly.community.CommunityMemberJoinedEvent;
import com.songly.content.PostCommentedEvent;
import com.songly.content.PostLikedEvent;
import com.songly.content.PostSharedEvent;
import com.songly.identity.UserRegisteredEvent;
import com.songly.social.FriendRequestAcceptedEvent;
import com.songly.social.FriendRequestSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Jedyny kontakt modułu analytics z resztą systemu: te same zdarzenia Springa,
 * które konsumuje moduł notification (Claude.md §2) — bez sięgania po encje
 * ani serwisy innych modułów.
 */
@Component
class AnalyticsEventListener {

	private final AnalyticsService analyticsService;

	AnalyticsEventListener(AnalyticsService analyticsService) {
		this.analyticsService = analyticsService;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onUserRegistered(UserRegisteredEvent event) {
		analyticsService.record(AnalyticsEventType.USER_REGISTERED, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostLiked(PostLikedEvent event) {
		analyticsService.record(AnalyticsEventType.POST_LIKED, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostCommented(PostCommentedEvent event) {
		analyticsService.record(AnalyticsEventType.POST_COMMENTED, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostShared(PostSharedEvent event) {
		analyticsService.record(AnalyticsEventType.POST_SHARED, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onFriendRequestSent(FriendRequestSentEvent event) {
		analyticsService.record(AnalyticsEventType.FRIEND_REQUEST_SENT, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onFriendRequestAccepted(FriendRequestAcceptedEvent event) {
		analyticsService.record(AnalyticsEventType.FRIEND_REQUEST_ACCEPTED, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommunityMemberJoined(CommunityMemberJoinedEvent event) {
		analyticsService.record(AnalyticsEventType.COMMUNITY_MEMBER_JOINED, event.occurredAt());
	}
}
