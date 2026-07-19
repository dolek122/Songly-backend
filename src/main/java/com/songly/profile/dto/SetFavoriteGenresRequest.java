package com.songly.profile.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.Set;
import java.util.UUID;

public record SetFavoriteGenresRequest(@NotEmpty Set<UUID> genreIds) {
}
