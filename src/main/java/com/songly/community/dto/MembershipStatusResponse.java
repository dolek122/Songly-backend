package com.songly.community.dto;

import com.songly.community.CommunityRole;
import java.time.Instant;

/**
 * Własny status w społeczności (ADR 0015). Pola są znaczące tylko dla swojego
 * statusu: {@code role} i {@code joinedAt} przy {@code MEMBER}, {@code ban}
 * przy {@code BANNED}; poza tym {@code null}.
 */
public record MembershipStatusResponse(CommunityMembershipStatus status, CommunityRole role, Instant joinedAt,
		BanDetailsResponse ban) {
}
