package com.songly.profile.dto;

import java.util.UUID;

public record ProfileResponse(UUID userId, String displayName, String bio, String avatarUrl) {
}
