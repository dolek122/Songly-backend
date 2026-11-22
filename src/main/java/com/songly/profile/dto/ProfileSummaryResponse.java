package com.songly.profile.dto;

import java.util.UUID;

public record ProfileSummaryResponse(UUID userId, String displayName, String avatarUrl, boolean suspended) {
}
