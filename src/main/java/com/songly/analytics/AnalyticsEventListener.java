package com.songly.analytics;

import com.songly.community.CommunityBanAppealAcceptedEvent;
import com.songly.community.CommunityBanAppealRejectedEvent;
import com.songly.community.CommunityBanAppealSubmittedEvent;
import com.songly.community.CommunityMemberBannedEvent;
import com.songly.community.CommunityMemberJoinedEvent;
import com.songly.content.CommunityPostRemovedEvent;
import com.songly.content.PostCommentedEvent;
import com.songly.content.PostLikedEvent;
import com.songly.content.PostReportDismissedEvent;
import com.songly.content.PostReportedEvent;
import com.songly.content.PostSharedEvent;
import com.songly.identity.UserRegisteredEvent;
import com.songly.social.FriendRequestAcceptedEvent;
import com.songly.social.FriendRequestSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Jedyny kontakt modułu analytics z resztą systemu: te same zdarzenia Springa,
 * które konsumuje moduł notification (Claude.md §2) — bez sięgania po encje ani
 * serwisy innych modułów.
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

	/**
	 * Moderacja (ADR 0011–0018) nie zostawiała dotąd ani jednego śladu w analityce,
	 * więc kilka decyzji odłożonych „do czasu, aż dane pokażą" nie miało skąd wziąć
	 * tych danych (ADR 0019). Zdarzenia są te same, które konsumuje notification —
	 * bez nowych zdarzeń i bez dotykania modułów, które je publikują.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostReported(PostReportedEvent event) {
		analyticsService.record(AnalyticsEventType.POST_FIRST_REPORTED, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostReportDismissed(PostReportDismissedEvent event) {
		analyticsService.record(AnalyticsEventType.POST_REPORT_DISMISSED, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommunityPostRemoved(CommunityPostRemovedEvent event) {
		analyticsService.record(AnalyticsEventType.COMMUNITY_POST_REMOVED, event.occurredAt());
	}

	/**
	 * Rozgałęzienie po terminie, bo analityka liczy same typy (ADR 0004) — inaczej
	 * nie dałoby się sprawdzić, czy bany czasowe się przyjęły.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommunityMemberBanned(CommunityMemberBannedEvent event) {
		analyticsService.record(event.expiresAt() == null
				? AnalyticsEventType.COMMUNITY_BAN_ISSUED_PERMANENT
				: AnalyticsEventType.COMMUNITY_BAN_ISSUED_TEMPORARY, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onBanAppealSubmitted(CommunityBanAppealSubmittedEvent event) {
		analyticsService.record(AnalyticsEventType.BAN_APPEAL_SUBMITTED, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onBanAppealAccepted(CommunityBanAppealAcceptedEvent event) {
		analyticsService.record(AnalyticsEventType.BAN_APPEAL_ACCEPTED, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onBanAppealRejected(CommunityBanAppealRejectedEvent event) {
		analyticsService.record(AnalyticsEventType.BAN_APPEAL_REJECTED, event.occurredAt());
	}
}
