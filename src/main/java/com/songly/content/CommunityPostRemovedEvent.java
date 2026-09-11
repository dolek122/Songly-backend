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
 */
public record CommunityPostRemovedEvent(UUID communityId, UUID postAuthorId, UUID moderatorId, Instant occurredAt) {
}
