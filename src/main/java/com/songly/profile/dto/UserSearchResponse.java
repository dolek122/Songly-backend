package com.songly.profile.dto;

import java.util.UUID;

public record UserSearchResponse(UUID userId, String displayName, String avatarUrl) {
}
