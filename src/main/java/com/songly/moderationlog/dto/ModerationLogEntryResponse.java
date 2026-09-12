package com.songly.moderationlog.dto;

import com.songly.moderationlog.ModerationAction;
import java.time.Instant;
import java.util.UUID;

/**
 * Wpis historii moderacji (ADR 0024).
 *
 * <p>
 * {@code actorId} bywa {@code null} (konto decydenta skasowane),
 * {@code communityId} przy decyzjach platformowych, a {@code expiresAt} i
 * {@code reportCount} są znaczące tylko dla części akcji — patrz
 * {@link ModerationAction}. Bez nazw: moduł nie zależy od {@code profile}, a
 * klient i tak dołoży je z wyszukiwarki profili.
 */
public record ModerationLogEntryResponse(ModerationAction action, UUID communityId, UUID subjectId, UUID actorId,
		Instant expiresAt, Integer reportCount, Instant occurredAt) {
}
