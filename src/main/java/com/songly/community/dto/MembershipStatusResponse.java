package com.songly.community.dto;

import com.songly.community.CommunityRole;
import java.time.Instant;

public record MembershipStatusResponse(CommunityMembershipStatus status, CommunityRole role, Instant joinedAt,
		BanDetailsResponse ban) {
}
