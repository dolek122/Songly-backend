package com.songly.identity.dto;

import com.songly.identity.SuspensionAppealStatus;
import java.time.Instant;

public record SuspensionResponse(boolean suspended, String reason, Instant suspendedAt, Instant expiresAt,
		SuspensionAppealStatus appealStatus, Instant appealedAt) {
}
