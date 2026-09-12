package com.songly.community;

import java.time.Instant;
import java.util.UUID;

/**
 * Ban zdjęty ręcznie przez moderację.
 *
 * <p>
 * Powstało dla historii moderacji (ADR 0024): bez tego zdarzenia dziennik
 * pokazywałby same nałożenia i nie dałoby się odróżnić „zbanowany trzy razy" od
 * „zbanowany trzy razy i trzy razy ułaskawiony". Nie powstaje przy wygaśnięciu
 * terminu — wygasanie jest leniwe (ADR 0014) i nie ma chwili, w której
 * cokolwiek się dzieje.
 *
 * <p>
 * Publikowane obok {@code CommunityBanAppealAcceptedEvent}, nie zamiast: tamto
 * mówi <i>dlaczego</i> ban zniknął i służy powiadomieniu, to mówi <i>że</i>
 * zniknął i służy dziennikowi.
 */
public record CommunityBanLiftedEvent(UUID communityId, UUID userId, UUID moderatorId, Instant occurredAt) {
}
