package com.songly.moderationlog;

import com.songly.community.CommunityBanAppealAcceptedEvent;
import com.songly.community.CommunityBanAppealRejectedEvent;
import com.songly.community.CommunityBanAppealSubmittedEvent;
import com.songly.community.CommunityBanLiftedEvent;
import com.songly.community.CommunityMemberBannedEvent;
import com.songly.community.CommunityMemberRoleChangedEvent;
import com.songly.content.CommunityPostRemovedEvent;
import com.songly.content.PostReportDismissedEvent;
import com.songly.identity.UserSuspendedEvent;
import com.songly.identity.UserSuspensionAppealAcceptedEvent;
import com.songly.identity.UserSuspensionAppealRejectedEvent;
import com.songly.identity.UserSuspensionAppealSubmittedEvent;
import com.songly.identity.UserSuspensionLiftedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Jedyny kontakt modułu z resztą systemu: te same zdarzenia domenowe, które
 * konsumują {@code notification} i {@code analytics} (Claude.md §2) — bez
 * sięgania po encje ani serwisy modułów, które je publikują.
 *
 * <p>
 * Wszystko {@code AFTER_COMMIT}: dziennik ma opisywać decyzje, które naprawdę
 * zapadły. Wpis o banie, którego transakcja się wycofała, byłby historią
 * zdarzenia, którego nie było — a historii nie da się potem poprawić, bo jest
 * append-only.
 *
 * <p>
 * Ograniczenia wolumenu dziennik dziedziczy za darmo, bo wszystkie bramki
 * siedzą u publikujących: tylko nowy ban (ADR 0014), tylko pierwsze zgłoszenie
 * (ADR 0012), tylko jedno odwołanie na sprawę (ADR 0017 i 0023), tylko
 * faktyczna zmiana roli (ADR 0016).
 */
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

	/**
	 * Usunięcie posta zapisujemy <b>dwa razy</b> i z dwóch różnych perspektyw (ADR
	 * 0027): raz jako decyzję wobec autora (z liczbą zgłoszeń, które post miał), a
	 * raz jako rozstrzygnięcie <b>na korzyść każdego zgłaszającego</b>.
	 *
	 * <p>
	 * Bez tego drugiego wpisu dziennik wiedział tylko, ile zgłoszeń post miał, ale
	 * nie czyje — a więc nie dawał odpowiedzi „czyje zgłoszenia bywają trafne", o
	 * którą upominały się ADR 0019 i 0025.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommunityPostRemoved(CommunityPostRemovedEvent event) {
		moderationLogService.record(ModerationAction.COMMUNITY_POST_REMOVED, event.communityId(), event.postAuthorId(),
				event.moderatorId(), null, event.reporterIds().size(), event.occurredAt());
		event.reporterIds().forEach(reporterId -> moderationLogService.record(ModerationAction.POST_REPORT_UPHELD,
				event.communityId(), reporterId, event.moderatorId(), null, null, event.occurredAt()));
	}

	/**
	 * Podmiotem jest <b>zgłaszający</b>, nie autor posta: to jego zgłoszenie
	 * zostało rozstrzygnięte, a dziennik ma odpowiadać na pytanie „co się stało ze
	 * sprawami tej osoby".
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostReportDismissed(PostReportDismissedEvent event) {
		moderationLogService.record(ModerationAction.POST_REPORT_DISMISSED, event.communityId(), event.reporterId(),
				event.moderatorId(), null, null, event.occurredAt());
	}

	/**
	 * Przy złożeniu odwołania podmiot i autor to ta sama osoba — i tak ma być:
	 * odwołanie jest jedyną decyzją w tym dzienniku, którą podejmuje osoba ukarana,
	 * a nie moderacja.
	 */
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
	void onCommunityMemberRoleChanged(CommunityMemberRoleChangedEvent event) {
		moderationLogService.record(
				event.moderationGranted()
						? ModerationAction.COMMUNITY_MODERATOR_GRANTED
						: ModerationAction.COMMUNITY_MODERATOR_REVOKED,
				event.communityId(), event.userId(), event.changedBy(), null, null, event.occurredAt());
	}

	/**
	 * Decyzje platformowe — bez społeczności, bo nie należą do żadnej (ADR 0022).
	 */
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
}
