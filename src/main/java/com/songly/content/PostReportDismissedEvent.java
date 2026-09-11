package com.songly.content;

import java.time.Instant;
import java.util.UUID;

/**
 * Zgłoszenie odrzucone — moderator uznał, że post zostaje.
 *
 * <p>
 * Zgłaszający dowiaduje się o decyzji, bo inaczej zgłaszanie jest wrzucaniem
 * kartek do zamkniętej skrzynki: nie wiadomo, czy ktokolwiek to przeczytał, a
 * po kilku takich razach przestaje się zgłaszać cokolwiek.
 */
public record PostReportDismissedEvent(UUID communityId, UUID postId, UUID reporterId, UUID moderatorId,
		Instant occurredAt) {
}
