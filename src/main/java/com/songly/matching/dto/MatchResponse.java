package com.songly.matching.dto;

import java.util.List;
import java.util.UUID;

/**
 * Jedna propozycja dopasowania.
 *
 * @param score
 *            siła dopasowania w (0, 1) — <b>to po tym sortujemy</b> i to jest
 *            liczba do pokazania. Ważony Jaccard (ADR 0029) przemnożony przez
 *            pewność (ADR 0033), czyli w praktyce: waga wspólnych gatunków
 *            podzielona przez wagę złączenia <i>powiększoną o stałą</i>.
 *            Jedynki nie da się już osiągnąć i to jest zamierzone — o niczyim
 *            guście nie wiemy wszystkiego.
 * @param confidence
 *            ile w tym wyniku jest danych, a ile domysłu: 0 przy profilach
 *            praktycznie pustych, blisko 1 przy bogatych. Wystawione, żeby
 *            klient mógł nie pokazywać procentu, którego nie ma czym poprzeć
 *            („oparte na jednym gatunku") — <b>nie</b> do sortowania, bo
 *            sortowanie jest już zrobione.
 * @param sharedGenreIds
 *            wspólne gatunki — konkret, który wyjaśnia wynik lepiej niż sam
 *            procent.
 */
public record MatchResponse(UUID userId, String displayName, String avatarUrl, double score, double confidence,
		List<UUID> sharedGenreIds) {
}
