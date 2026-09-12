package com.songly.community;

import java.time.Instant;
import java.util.UUID;

/**
 * Administracja platformy utrzymała ban społeczności (ADR 0028). Dla tego banu
 * to koniec drogi: nad administracją nie ma nikogo, więc trzeciej instancji nie
 * ma i nie będzie.
 */
public record CommunityBanAppealEscalationRejectedEvent(UUID communityId, UUID userId, UUID adminId,
		Instant occurredAt) {
}
