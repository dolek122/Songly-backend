package com.songly.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reporter_decisions")
class ReporterDecision {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "community_id", nullable = false)
	private UUID communityId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReporterDecisionOutcome outcome;

	@Column(name = "decided_at", nullable = false)
	private Instant decidedAt;

	protected ReporterDecision() {
	}

	ReporterDecision(UUID id, UUID userId, UUID communityId, ReporterDecisionOutcome outcome, Instant decidedAt) {
		this.id = id;
		this.userId = userId;
		this.communityId = communityId;
		this.outcome = outcome;
		this.decidedAt = decidedAt;
	}
}
