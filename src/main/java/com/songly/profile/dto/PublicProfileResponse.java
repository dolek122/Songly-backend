package com.songly.profile.dto;

import java.util.List;
import java.util.UUID;

public record PublicProfileResponse(UUID userId, String displayName, String bio, String avatarUrl,
		List<GenreResponse> favoriteGenres) {
}
