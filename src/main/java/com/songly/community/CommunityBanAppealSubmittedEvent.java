package com.songly.community;

import java.time.Instant;
import java.util.UUID;

/**
 * Zbanowany odwołał się od bana (ADR 0017). Adresatami powiadomienia są wszyscy
 * moderatorzy — jak przy zgłoszeniu posta (ADR 0012): kolejka bez powiadomienia
 * to narzędzie, którego nic nie uruchamia.
 *
 * <p>
 * Publikowane co najwyżej raz na ban: odwołanie jest jedno (drugie kończy się
 * 409), więc bramka anty-spamowa siedzi u publikującego, jak wszędzie.
 */
public record CommunityBanAppealSubmittedEvent(UUID communityId, UUID userId, Instant occurredAt) {
}
