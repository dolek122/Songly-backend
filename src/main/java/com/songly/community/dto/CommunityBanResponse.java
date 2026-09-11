package com.songly.community.dto;

import com.songly.community.BanAppealStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code bannedBy} bywa {@code null} — moderator, który ban nałożył, mógł
 * skasować konto. Ban należy do społeczności, więc przeżywa swojego autora.
 *
 * <p>
 * {@code expiresAt} — {@code null} to ban bezterminowy (ADR 0014). Bany wygasłe
 * w ogóle nie występują na liście.
 *
 * <p>
 * Pola odwołania (ADR 0017) — {@code appealStatus} {@code null} = brak
 * odwołania. Lista banów jest jednocześnie kolejką odwołań: to <b>jedyne</b>
 * miejsce, w którym moderacja czyta treść odwołania — mail i powiadomienie
 * mówią tylko, że jest po co zajrzeć (żadnych cudzych tekstów w kanałach push,
 * ta sama zasada co przy powodzie bana w ADR 0015).
 */
public record CommunityBanResponse(UUID userId, String displayName, String avatarUrl, String reason, UUID bannedBy,
		Instant createdAt, Instant expiresAt, String appealText, Instant appealedAt, BanAppealStatus appealStatus) {
}
