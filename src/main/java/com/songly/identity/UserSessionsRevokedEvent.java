package com.songly.identity;

import java.util.UUID;

/**
 * Wszystkie sesje konta odwołane naraz: zmiana hasła, reset hasła albo wykryta
 * kradzież refresh tokenu (Claude.md §9). Moduły trzymające coś, co działa „w
 * imieniu sesji" bez niej samej — dziś urządzenia push (ADR 0040) — muszą to
 * odpiąć, inaczej telefon odcięty od konta dalej dostawałby powiadomienia.
 *
 * <p>
 * Publikowane synchronicznie, w transakcji rewokacji: odpięcie ma się
 * zatwierdzić albo wycofać razem z nią.
 */
public record UserSessionsRevokedEvent(UUID userId) {
}
