package com.songly.content;

import java.time.Instant;
import java.util.Set;
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
 * {@code reporterIds} to osoby, które zgłosiły ten post <b>przed</b> jego
 * usunięciem. Kasowanie posta zabiera kaskadą jego zgłoszenia, więc po fakcie
 * nie ma już czego czytać — a to właśnie usunięcie posta jest momentem, w
 * którym zgłoszenie okazuje się <b>trafne</b>.
 *
 * <p>
 * Wcześniej (ADR 0024) było tu samo {@code reportCount}, co wystarczało do
 * historii („post usunięty, miał 3 zgłoszenia"), ale nie dawało odpowiedzi
 * „czyje zgłoszenia bywają trafne" — dokładnie brak zapisany w ADR 0019 i 0025.
 * Liczba nadal jest dostępna jako {@code reporterIds().size()}, więc nie ma
 * dwóch źródeł prawdy.
 */
public record CommunityPostRemovedEvent(UUID communityId, UUID postAuthorId, UUID moderatorId, Set<UUID> reporterIds,
		Instant occurredAt) {
}
