package com.songly.community.dto;

import com.songly.community.CommunityRole;
import java.time.Instant;
import java.util.UUID;

public record CommunityMemberResponse(UUID userId, String displayName, CommunityRole role, Instant joinedAt) {
}
