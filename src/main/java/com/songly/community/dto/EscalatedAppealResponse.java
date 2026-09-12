package com.songly.community.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Sprawa czekająca na drugą instancję (ADR 0028) — kolejka administracji.
 *
 * <p>
 * Zawiera powód banu <b>i</b> treść odwołania, bo administrator rozstrzyga spór
 * między dwiema stronami i musi znać obie. To jedyne miejsce, w którym ktoś
 * spoza społeczności widzi jej powody banów — i wyłącznie dla spraw, które sam
 * zbanowany do niego skierował. Kanał jest przy tym pull: nikt tych tekstów nie
 * doręcza administratorowi mailem (ADR 0015).
 */
public record EscalatedAppealResponse(UUID communityId, String communityName, UUID userId, String reason,
		Instant bannedAt, Instant expiresAt, String appealText, Instant appealedAt, Instant escalatedAt) {
}
