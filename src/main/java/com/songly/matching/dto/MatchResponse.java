package com.songly.matching.dto;

import java.util.List;
import java.util.UUID;

public record MatchResponse(UUID userId, String displayName, String avatarUrl, double score,
		List<UUID> sharedGenreIds) {
}
