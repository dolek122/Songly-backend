package com.songly.content.dto;

import com.songly.content.ReportReason;
import java.time.Instant;
import java.util.UUID;

/**
 * Pozycja w kolejce moderacji: zgłoszenie razem z postem, którego dotyczy —
 * moderator nie powinien musieć dopytywać o treść osobnym żądaniem.
 *
 * <p>
 * {@code reportCount} to liczba niezależnych osób, które zgłosiły ten post.
 * Ważniejsza niż samo zgłoszenie, bo jedno zgłoszenie bywa złośliwe, a
 * kilkanaście raczej nie.
 *
 * <p>
 * {@code reporterUpheldCount} i {@code reporterDismissedCount} to trafność tej
 * osoby <b>w tej społeczności</b> (ADR 0025, uzupełnione w 0027) — waga podana
 * moderatorowi do oceny, nie odjęta za niego. Dopiero obie liczby razem coś
 * znaczą: „5 uwzględnionych, 1 odrzucone" czyta się zupełnie inaczej niż „0 i
 * 1", a przed ADR 0027 widać było tylko drugą z nich.
 *
 * <p>
 * Zasięg jest celowo węższy niż przy limicie liczonym globalnie: moderator nie
 * ma prawa dowiedzieć się, co zgłaszający robił w innych społecznościach
 * (Claude.md §8).
 */
public record PostReportResponse(UUID id, ReportReason reason, String details, UUID reporterId,
		String reporterDisplayName, Instant createdAt, long reportCount, int reporterUpheldCount,
		int reporterDismissedCount, PostResponse post) {
}
