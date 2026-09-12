package com.songly.community;

import java.time.Instant;
import java.util.UUID;

/**
 * Zbanowany przekazał odrzucone odwołanie do <b>administracji platformy</b>
 * (ADR 0028). Adresatami powiadomienia są wszyscy administratorzy — nie mają w
 * aplikacji żadnej kolejki, do której by sami zaglądali, więc bez tego sprawa
 * leżałaby bezterminowo (ten sam argument co przy odwołaniach od zawieszenia,
 * ADR 0023).
 *
 * <p>
 * Co najwyżej raz na ban: eskalacja jest możliwa tylko ze stanu „odwołanie
 * odrzucone", a z tego stanu wychodzi się bezpowrotnie.
 */
public record CommunityBanAppealEscalatedEvent(UUID communityId, UUID userId, Instant occurredAt) {
}
