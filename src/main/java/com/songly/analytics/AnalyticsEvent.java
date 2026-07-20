package com.songly.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Celowo bez odniesienia do konkretnego usera/zasobu — to tylko zliczenia "co
 * się dzieje", nie dane behawioralne per użytkownik (Claude.md §9).
 */
@Entity
@Table(name = "analytics_events")
class AnalyticsEvent {

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private AnalyticsEventType type;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	protected AnalyticsEvent() {
	}

	AnalyticsEvent(UUID id, AnalyticsEventType type, Instant occurredAt) {
		this.id = id;
		this.type = type;
		this.occurredAt = occurredAt;
	}

	UUID getId() {
		return id;
	}

	AnalyticsEventType getType() {
		return type;
	}

	Instant getOccurredAt() {
		return occurredAt;
	}
}
