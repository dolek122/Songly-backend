package com.songly.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Ktoś dostał albo stracił uprawnienia administratora platformy (ADR 0031).
 *
 * <p>
 * Publikowane <b>tylko przy faktycznej zmianie</b> — nadanie roli komuś, kto ją
 * ma, jest idempotentnym brakiem zmiany (ADR 0021), więc nie zostawia śladu. Ta
 * sama zasada co przy „tylko nowy ban" (ADR 0014) i przy rolach w
 * społecznościach (ADR 0016): dziennik ma zapisywać zmiany stanu, nie
 * kliknięcia.
 *
 * <p>
 * Kierunek jedzie jako {@code granted}, bo w tej roli nie ma stopni — jest albo
 * jej nie ma. Pary osobnych zdarzeń też by tu wystarczyły, ale boolean trzyma
 * kształt identyczny z {@code CommunityMemberRoleChangedEvent}, a konsument
 * (dziennik moderacji) i tak rozbija go na dwie akcje.
 */
public record AdminRoleChangedEvent(UUID userId, UUID actorId, boolean granted, Instant occurredAt) {
}
