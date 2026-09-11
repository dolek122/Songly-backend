package com.songly.community.dto;

/**
 * Stan pytającego wobec społeczności. Stany wykluczają się wzajemnie: ban usuwa
 * członkostwo i blokuje powrót, więc nie da się być członkiem i zbanowanym
 * naraz, a ban wygasły nie liczy się nigdzie (ADR 0014) — czyli czyta się jak
 * {@code NONE}.
 */
public enum CommunityMembershipStatus {
	MEMBER, BANNED, NONE
}
