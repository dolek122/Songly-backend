package com.songly.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Jedno rozstrzygnięcie zgłoszenia, zapisane w chwili decyzji (ADR 0027), <b>z
 * datą</b> (ADR 0030).
 *
 * <p>
 * Dlaczego wiersz na decyzję, a nie licznik jak do ADR 0030: licznik nie wie,
 * kiedy przyszło to, co zliczył, więc nie da się z niego policzyć „odrzuceń z
 * ostatnich 90 dni". Dołożenie do licznika jednej daty dawałoby okno
 * <b>skokowe</b> — przy zerowaniu całego wiersza na granicy okna odrzucenie z
 * dnia poprzedniego przepadałoby razem z rocznymi, więc cierpliwy troll
 * dostawałby czystą kartę, celując w moment przesunięcia.
 *
 * <p>
 * Wiersz nie jest powiązany z postem i to jest powód jego istnienia,
 * odziedziczony po ADR 0027: uwzględnienie zgłoszenia = usunięcie posta =
 * kaskadowe usunięcie jego zgłoszeń, więc liczenie po {@code post_reports}
 * gubiło historię dokładnie wtedy, gdy zgłoszenie okazywało się <b>trafne</b>.
 */
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
