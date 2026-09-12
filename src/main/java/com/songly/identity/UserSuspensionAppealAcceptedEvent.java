package com.songly.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Odwołanie uznane — czyli zdjęcie zawieszenia z konta, które miało wiszące
 * odwołanie (ADR 0023). Osobnego „endpointu uznania" nie ma: uznanie odwołania
 * i zdjęcie zawieszenia to ta sama decyzja, więc wyraża ją istniejący
 * {@code DELETE} na zawieszeniu.
 *
 * <p>
 * Zastępuje wtedy {@link UserSuspensionLiftedEvent}, żeby odwołujący dostał
 * jedną wiadomość mówiącą, <i>dlaczego</i> konto wróciło, a nie dwie o tym
 * samym.
 */
public record UserSuspensionAppealAcceptedEvent(UUID userId, UUID actorId, Instant occurredAt) {
}
