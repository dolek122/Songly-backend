package com.songly.community.dto;

import java.time.Instant;

/**
 * Szczegóły własnego bana, widziane przez zbanowanego (ADR 0015).
 *
 * <p>
 * Powód i termin — bez tożsamości moderatora. Ban należy do społeczności, nie
 * do osoby, która go nałożyła (ADR 0011), więc zbanowanemu prezentujemy decyzję
 * społeczności: powód służy zrozumieniu, wskazanie palcem konkretnego
 * moderatora służyłoby głównie odwetowi.
 *
 * <p>
 * {@code expiresAt} — {@code null} to ban bezterminowy (ADR 0014).
 */
public record BanDetailsResponse(String reason, Instant createdAt, Instant expiresAt) {
}
