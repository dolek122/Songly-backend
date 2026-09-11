package com.songly.community;

import java.time.Instant;
import java.util.UUID;

/**
 * Publikowane po nałożeniu <b>nowego</b> banu — nie przy ponownym banie osoby,
 * która już go ma. Ban jest idempotentny (ADR 0011), więc bez tego rozróżnienia
 * każde powtórzone kliknięcie moderatora dokładałoby zbanowanemu kolejne
 * powiadomienie o tym samym.
 */
public record CommunityMemberBannedEvent(UUID communityId, UUID userId, UUID moderatorId, Instant occurredAt) {
}
