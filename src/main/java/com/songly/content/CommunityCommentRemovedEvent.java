package com.songly.content;

import java.time.Instant;
import java.util.UUID;

/**
 * Moderator usunął cudzy komentarz pod postem w społeczności.
 *
 * <p>
 * Publikowane <b>tylko</b> gdy kasuje ktoś inny niż autor, z tego samego powodu
 * co {@link CommunityPostRemovedEvent}: skasowanie własnego komentarza nie jest
 * decyzją moderacyjną.
 *
 * <p>
 * Niesie {@code postId}, bo — inaczej niż przy usuniętym poście — post nadal
 * istnieje i to on jest miejscem, do którego powiadomienie może prowadzić.
 */
public record CommunityCommentRemovedEvent(UUID communityId, UUID postId, UUID commentAuthorId, UUID moderatorId,
		Instant occurredAt) {
}
