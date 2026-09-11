package com.songly.identity.dto;

import java.util.List;
import java.util.UUID;

/**
 * Pełny wykaz administratorów (ADR 0021).
 *
 * <p>
 * Same identyfikatory, bez nazw: moduł {@code identity} nie może zależeć od
 * {@code profile}, bo {@code profile} zależy od niego (cykl złapałby test
 * granic modułów, Claude.md §2). Nazwy dołoży klient z modułu profile — ta sama
 * konwencja co {@code CommunityMembership.moderatorIds}.
 */
public record AdminListResponse(List<UUID> userIds) {
}
