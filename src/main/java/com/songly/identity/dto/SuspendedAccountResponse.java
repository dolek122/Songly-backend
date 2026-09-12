package com.songly.identity.dto;

import com.songly.identity.SuspensionAppealStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Wiersz wykazu zawieszonych kont dla administratora (ADR 0022). Bez adresu
 * e-mail: do identyfikacji służy {@code userId}, a wykaz ma pokazywać stan
 * moderacyjny, nie być wyszukiwarką danych kontaktowych (Claude.md §9).
 *
 * <p>
 * Pola odwołania (ADR 0023) sprawiają, że ten wykaz jest <b>jednocześnie
 * kolejką odwołań</b> — i jest to <b>jedyne</b> miejsce, w którym administracja
 * czyta treść odwołania. Mail mówi tylko, że jest po co zajrzeć: tekst od
 * użytkownika nie jedzie kanałem push (ta sama zasada co przy powodzie
 * zawieszenia, ADR 0015 i 0017).
 */
public record SuspendedAccountResponse(UUID userId, String reason, Instant suspendedAt, Instant expiresAt,
		String appealText, Instant appealedAt, SuspensionAppealStatus appealStatus) {
}
