package com.songly.moderationlog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Jeden wpis w historii moderacji (ADR 0024) — niezmienny po zapisie.
 *
 * <p>
 * Celowo <b>bez powodu sankcji</b>. Powód żyje na wierszu kary, dopóki kara
 * trwa, i jest tekstem moderatora, którego kanały push nie przenoszą (ADR
 * 0015). Żeby go tu zapisać, trzeba by go wnieść do zdarzenia domenowego — a
 * wtedy byłby jeden nieuważny krok od maila, czyli od dziury, którą ADR 0015
 * zamknął. Dziennik odpowiada więc na „czy i ile razy", nie na „dlaczego".
 * Treści odwołań nie kopiujemy z tego samego powodu.
 */
@Entity
@Table(name = "moderation_log_entries")
class ModerationLogEntry {

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private ModerationAction action;

	/** {@code null} dla decyzji platformowych. */
	@Column(name = "community_id")
	private UUID communityId;

	@Column(name = "subject_id", nullable = false)
	private UUID subjectId;

	/** {@code null} po skasowaniu konta osoby, która decyzję podjęła. */
	@Column(name = "actor_id")
	private UUID actorId;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "report_count")
	private Integer reportCount;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	protected ModerationLogEntry() {
	}

	ModerationLogEntry(UUID id, ModerationAction action, UUID communityId, UUID subjectId, UUID actorId,
			Instant expiresAt, Integer reportCount, Instant occurredAt) {
		this.id = id;
		this.action = action;
		this.communityId = communityId;
		this.subjectId = subjectId;
		this.actorId = actorId;
		this.expiresAt = expiresAt;
		this.reportCount = reportCount;
		this.occurredAt = occurredAt;
	}

	ModerationAction getAction() {
		return action;
	}

	UUID getCommunityId() {
		return communityId;
	}

	UUID getSubjectId() {
		return subjectId;
	}

	UUID getActorId() {
		return actorId;
	}

	Instant getExpiresAt() {
		return expiresAt;
	}

	Integer getReportCount() {
		return reportCount;
	}

	Instant getOccurredAt() {
		return occurredAt;
	}
}
