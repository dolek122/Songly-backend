package com.songly.community.dto;

import com.songly.community.BanAppealStatus;
import java.time.Instant;

public record BanDetailsResponse(String reason, Instant createdAt, Instant expiresAt, BanAppealStatus appealStatus,
		Instant appealedAt) {
}
