package com.songly.moderationlog.dto;

import com.songly.moderationlog.ModerationAction;
import java.time.Instant;
import java.util.UUID;

public record ModerationLogEntryResponse(ModerationAction action, UUID communityId, UUID subjectId, UUID actorId,
		Instant expiresAt, Integer reportCount, Instant occurredAt) {
}
