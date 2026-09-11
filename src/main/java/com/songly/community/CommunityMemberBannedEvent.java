package com.songly.community;

import java.time.Instant;
import java.util.UUID;

/**
 * Publikowane po nałożeniu banu, który zmienia stan z „niezbanowany" na
 * „zbanowany": pierwszym albo nadpisującym wygasły (ADR 0014). Nie przy
 * ponownym banie osoby z banem aktywnym — ban jest idempotentny (ADR 0011),
 * więc bez tego rozróżnienia każde powtórzone kliknięcie moderatora dokładałoby
 * zbanowanemu kolejne powiadomienie o tym samym.
 *
 * <p>
 * {@code expiresAt} — {@code null} to ban bezterminowy. Skrzynka powiadomień z
 * tego nie korzysta (płaski model, ADR 0012), ale mail mówi zbanowanemu, na jak
 * długo — ban czasowy, który wygląda na dożywotni, mija się z celem.
 */
public record CommunityMemberBannedEvent(UUID communityId, UUID userId, UUID moderatorId, Instant expiresAt,
		Instant occurredAt) {
}
