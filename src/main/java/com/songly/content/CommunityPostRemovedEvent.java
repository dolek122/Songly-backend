package com.songly.content;

import java.time.Instant;
import java.util.UUID;

/**
 * Moderator usunął cudzy post w społeczności.
 *
 * <p>
 * Publikowane <b>tylko</b> gdy kasuje ktoś inny niż autor — skasowanie własnego
 * posta nie jest decyzją moderacyjną i nie ma o czym zawiadamiać.
 *
 * <p>
 * Zasobem jest {@code communityId}, a nie {@code postId}: post w tym momencie
 * przestaje istnieć, więc identyfikator, w który klient miałby kliknąć,
 * prowadziłby donikąd.
 *
 * <p>
 * {@code reportCount} to liczba zgłoszeń, które post miał w chwili usunięcia
 * (ADR 0024). Bez niej nie dało się policzyć, ile zgłoszeń było trafnych —
 * kasowanie posta zabiera kaskadą jego zgłoszenia, więc po fakcie nie ma już
 * czego zliczyć (dokładnie ta luka, którą ADR 0019 zapisał w konsekwencjach).
 */
public record CommunityPostRemovedEvent(UUID communityId, UUID postAuthorId, UUID moderatorId, int reportCount,
		Instant occurredAt) {
}
