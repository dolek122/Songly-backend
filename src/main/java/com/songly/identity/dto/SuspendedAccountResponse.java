package com.songly.identity.dto;

import com.songly.identity.SuspensionAppealStatus;
import java.time.Instant;
import java.util.UUID;

public record SuspendedAccountResponse(UUID userId, String reason, Instant suspendedAt, Instant expiresAt,
		String appealText, Instant appealedAt, SuspensionAppealStatus appealStatus) {
}
