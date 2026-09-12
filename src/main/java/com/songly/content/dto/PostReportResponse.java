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
 * osoby <b>w tej społeczności</b> i <b>w oknie historii</b> (ADR 0025,
 * uzupełnione w 0027 i 0030) — waga podana moderatorowi do oceny, nie odjęta za
 * niego. Dopiero obie liczby razem coś znaczą: „5 uwzględnionych, 1 odrzucone"
 * czyta się zupełnie inaczej niż „0 i 1", a przed ADR 0027 widać było tylko
 * drugą z nich.
 *
 * <p>
 * Oba ograniczenia są celowe i różne. <b>Społeczność</b>, bo moderator nie ma
 * prawa dowiedzieć się, co zgłaszający robił w obcych społecznościach
 * (Claude.md §8) — przydział liczy to szerzej, globalnie. <b>Okno</b>
 * (domyślnie 90 dni, {@code songly.content.report.history-window}), bo te same
 * liczby sterują karą, a kara nie jest bezterminowa: liczba bez skali czasu nie
 * jest przy tym wagą, którą człowiek umie rozważyć — „3 odrzucenia" znaczy coś
 * innego w tym tygodniu, a coś innego dwa lata temu.
 */
public record PostReportResponse(UUID id, ReportReason reason, String details, UUID reporterId,
		String reporterDisplayName, Instant createdAt, long reportCount, int reporterUpheldCount,
		int reporterDismissedCount, PostResponse post) {
}
