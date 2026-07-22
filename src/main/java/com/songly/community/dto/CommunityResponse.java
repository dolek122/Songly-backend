package com.songly.community.dto;

import com.songly.community.CommunityRole;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code myRole} jest {@code null}, gdy przeglądający nie jest członkiem.
 * {@code ownerDisplayName}/{@code ownerAvatarUrl} dokładamy z modułu profile przez
 * {@link com.songly.profile.ProfileDirectory}, żeby klient mógł pokazać założyciela
 * bez osobnego zapytania; {@code ownerAvatarUrl} bywa {@code null} (brak avatara).
 */
public record CommunityResponse(UUID id, String name, String description, UUID ownerId, String ownerDisplayName,
		String ownerAvatarUrl, long memberCount, CommunityRole myRole, Instant createdAt) {
}
