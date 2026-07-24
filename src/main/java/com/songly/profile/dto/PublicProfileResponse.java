package com.songly.profile.dto;

import java.util.List;
import java.util.UUID;

/**
 * Publiczny profil innego użytkownika: dane profilowe (§5 — {@code Profile} to
 * część publiczna, oddzielona od danych logowania) wraz z ulubionymi gatunkami.
 * Świadomie nie niesie danych o zachowaniu (co teraz słucha) — to dane wrażliwe
 * z osobnym modelem prywatności (Claude.md §9), poza zakresem tego widoku.
 */
public record PublicProfileResponse(UUID userId, String displayName, String bio, String avatarUrl,
		List<GenreResponse> favoriteGenres) {
}
