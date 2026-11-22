package com.songly.moderationlog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "moderation_log_entries")
class ModerationLogEntry {

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private ModerationAction action;

	@Column(name = "community_id")
	private UUID communityId;

	@Column(name = "subject_id", nullable = false)
	private UUID subjectId;

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
