package com.songly.community;

import java.time.Instant;
import java.util.UUID;

/**
 * Odwołanie odrzucone — ban zostaje w mocy (ADR 0017). Dla tego bana decyzja
 * jest ostateczna: jedno odwołanie na ban, świeże prawo daje dopiero nowy ban.
 */
public record CommunityBanAppealRejectedEvent(UUID communityId, UUID userId, UUID moderatorId, Instant occurredAt) {
}
