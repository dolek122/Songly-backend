package com.songly.content;

import java.time.Instant;
import java.util.UUID;

/**
 * Publikowane przy <b>pierwszym</b> zgłoszeniu danego posta, a nie przy każdym
 * — dokładniej: przy pierwszym, gdy post nie ma żadnego zgłoszenia czekającego
 * w kolejce (ADR 0049). Wcześniej liczyły się też zgłoszenia odrzucone, więc
 * post, który ktoś zgłosił ponownie po odrzuceniu poprzednich zgłoszeń, trafiał
 * do kolejki bez żadnego powiadomienia.
 *
 * <p>
 * Adresatami są wszyscy moderatorzy społeczności, więc bez tego ograniczenia
 * post zgłoszony przez dwadzieścia osób zasypywałby trzech moderatorów
 * sześćdziesięcioma powiadomieniami o jednej sprawie. Kolejne zgłoszenia i tak
 * podbijają licznik widoczny w kolejce ({@code reportCount}), a to on niesie
 * informację „ilu niezależnym osobom to przeszkadza".
 */
public record PostReportedEvent(UUID communityId, UUID postId, UUID reporterId, Instant occurredAt) {
}
