package com.songly.community;

import java.time.Instant;
import java.util.UUID;

/**
 * Odwołanie uznane — czyli unban osoby z wiszącym odwołaniem (ADR 0017).
 * Osobnego „endpointu uznania" nie ma: uznanie odwołania i zdjęcie bana to ta
 * sama decyzja, więc wyraża ją istniejący DELETE na banie.
 */
public record CommunityBanAppealAcceptedEvent(UUID communityId, UUID userId, UUID moderatorId, Instant occurredAt) {
}
