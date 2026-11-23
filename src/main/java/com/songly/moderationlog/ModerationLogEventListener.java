package com.songly.moderationlog;

import com.songly.community.CommunityBanAppealAcceptedEvent;
import com.songly.community.CommunityBanAppealEscalatedEvent;
import com.songly.community.CommunityBanAppealEscalationAcceptedEvent;
import com.songly.community.CommunityBanAppealEscalationRejectedEvent;
import com.songly.community.CommunityBanAppealRejectedEvent;
import com.songly.community.CommunityBanAppealSubmittedEvent;
import com.songly.community.CommunityBanLiftedEvent;
import com.songly.community.CommunityMemberBannedEvent;
import com.songly.community.CommunityMemberRoleChangedEvent;
import com.songly.content.CommunityCommentRemovedEvent;
import com.songly.content.CommunityPostRemovedEvent;
import com.songly.content.PostRemovedEvent;
import com.songly.content.PostReportDismissedEvent;
import com.songly.identity.AdminRoleChangedEvent;
import com.songly.identity.UserSuspendedEvent;
import com.songly.identity.UserSuspensionAppealAcceptedEvent;
import com.songly.identity.UserSuspensionAppealRejectedEvent;
import com.songly.identity.UserSuspensionAppealSubmittedEvent;
import com.songly.identity.UserSuspensionLiftedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class ModerationLogEventListener {

	private final ModerationLogService moderationLogService;

	ModerationLogEventListener(ModerationLogService moderationLogService) {
		this.moderationLogService = moderationLogService;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommunityMemberBanned(CommunityMemberBannedEvent event) {
		moderationLogService.record(ModerationAction.COMMUNITY_BAN_ISSUED, event.communityId(), event.userId(),
				event.moderatorId(), event.expiresAt(), null, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommunityBanLifted(CommunityBanLiftedEvent event) {
		moderationLogService.record(ModerationAction.COMMUNITY_BAN_LIFTED, event.communityId(), event.userId(),
				event.moderatorId(), null, null, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommunityPostRemoved(CommunityPostRemovedEvent event) {
		moderationLogService.record(ModerationAction.COMMUNITY_POST_REMOVED, event.communityId(), event.postAuthorId(),
				event.moderatorId(), null, event.reporterIds().size(), event.occurredAt());
		event.reporterIds().forEach(reporterId -> moderationLogService.record(ModerationAction.POST_REPORT_UPHELD,
				event.communityId(), reporterId, event.moderatorId(), null, null, event.occurredAt()));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostRemoved(PostRemovedEvent event) {
		moderationLogService.record(ModerationAction.POST_REMOVED, null, event.postAuthorId(), event.moderatorId(),
				null, event.reporterIds().size(), event.occurredAt());
		event.reporterIds().forEach(reporterId -> moderationLogService.record(ModerationAction.POST_REPORT_UPHELD, null,
				reporterId, event.moderatorId(), null, null, event.occurredAt()));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommunityCommentRemoved(CommunityCommentRemovedEvent event) {
		moderationLogService.record(ModerationAction.COMMUNITY_COMMENT_REMOVED, event.communityId(),
				event.commentAuthorId(), event.moderatorId(), null, null, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostReportDismissed(PostReportDismissedEvent event) {
		moderationLogService.record(ModerationAction.POST_REPORT_DISMISSED, event.communityId(), event.reporterId(),
				event.moderatorId(), null, null, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommunityBanAppealSubmitted(CommunityBanAppealSubmittedEvent event) {
		moderationLogService.record(ModerationAction.COMMUNITY_BAN_APPEAL_SUBMITTED, event.communityId(),
				event.userId(), event.userId(), null, null, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommunityBanAppealAccepted(CommunityBanAppealAcceptedEvent event) {
		moderationLogService.record(ModerationAction.COMMUNITY_BAN_APPEAL_ACCEPTED, event.communityId(), event.userId(),
				event.moderatorId(), null, null, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommunityBanAppealRejected(CommunityBanAppealRejectedEvent event) {
		moderationLogService.record(ModerationAction.COMMUNITY_BAN_APPEAL_REJECTED, event.communityId(), event.userId(),
				event.moderatorId(), null, null, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onBanAppealEscalated(CommunityBanAppealEscalatedEvent event) {
		moderationLogService.record(ModerationAction.COMMUNITY_BAN_APPEAL_ESCALATED, event.communityId(),
				event.userId(), event.userId(), null, null, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onBanEscalationAccepted(CommunityBanAppealEscalationAcceptedEvent event) {
		moderationLogService.record(ModerationAction.COMMUNITY_BAN_ESCALATION_ACCEPTED, event.communityId(),
				event.userId(), event.adminId(), null, null, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onBanEscalationRejected(CommunityBanAppealEscalationRejectedEvent event) {
		moderationLogService.record(ModerationAction.COMMUNITY_BAN_ESCALATION_REJECTED, event.communityId(),
				event.userId(), event.adminId(), null, null, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommunityMemberRoleChanged(CommunityMemberRoleChangedEvent event) {
		moderationLogService.record(
				event.moderationGranted()
						? ModerationAction.COMMUNITY_MODERATOR_GRANTED
						: ModerationAction.COMMUNITY_MODERATOR_REVOKED,
				event.communityId(), event.userId(), event.changedBy(), null, null, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onUserSuspended(UserSuspendedEvent event) {
		moderationLogService.record(ModerationAction.ACCOUNT_SUSPENDED, null, event.userId(), event.actorId(),
				event.expiresAt(), null, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onUserSuspensionLifted(UserSuspensionLiftedEvent event) {
		moderationLogService.record(ModerationAction.ACCOUNT_SUSPENSION_LIFTED, null, event.userId(), event.actorId(),
				null, null, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onSuspensionAppealSubmitted(UserSuspensionAppealSubmittedEvent event) {
		moderationLogService.record(ModerationAction.SUSPENSION_APPEAL_SUBMITTED, null, event.userId(), event.userId(),
				null, null, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onSuspensionAppealAccepted(UserSuspensionAppealAcceptedEvent event) {
		moderationLogService.record(ModerationAction.SUSPENSION_APPEAL_ACCEPTED, null, event.userId(), event.actorId(),
				null, null, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onSuspensionAppealRejected(UserSuspensionAppealRejectedEvent event) {
		moderationLogService.record(ModerationAction.SUSPENSION_APPEAL_REJECTED, null, event.userId(), event.actorId(),
				null, null, event.occurredAt());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onAdminRoleChanged(AdminRoleChangedEvent event) {
		moderationLogService.record(
				event.granted() ? ModerationAction.PLATFORM_ADMIN_GRANTED : ModerationAction.PLATFORM_ADMIN_REVOKED,
				null, event.userId(), event.actorId(), null, null, event.occurredAt());
	}
}
