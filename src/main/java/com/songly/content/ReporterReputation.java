package com.songly.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Trafność zgłoszeń jednej osoby w jednej społeczności (ADR 0027).
 *
 * <p>
 * Liczniki, a nie wyliczanie z {@code post_reports} na żądanie — bo usunięcie
 * posta zabiera kaskadą jego zgłoszenia, czyli historia trafnych zgłoszeń
 * ginęła dokładnie wtedy, gdy zgłoszenie okazywało się <b>trafne</b>. Licznik
 * nie jest powiązany z postem, więc przeżywa jego usunięcie.
 */
@Entity
@Table(name = "reporter_reputation")
@IdClass(ReporterReputationId.class)
class ReporterReputation {

	@Id
	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Id
	@Column(name = "community_id", nullable = false)
	private UUID communityId;

	@Column(nullable = false)
	private int upheld;

	@Column(nullable = false)
	private int dismissed;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ReporterReputation() {
	}

	ReporterReputation(UUID userId, UUID communityId, Instant updatedAt) {
		this.userId = userId;
		this.communityId = communityId;
		this.upheld = 0;
		this.dismissed = 0;
		this.updatedAt = updatedAt;
	}

	UUID getUserId() {
		return userId;
	}

	int getUpheld() {
		return upheld;
	}

	int getDismissed() {
		return dismissed;
	}

	void recordUpheld(Instant now) {
		this.upheld++;
		this.updatedAt = now;
	}

	void recordDismissed(Instant now) {
		this.dismissed++;
		this.updatedAt = now;
	}
}
