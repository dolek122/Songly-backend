package com.songly.notification.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateMailPreferencesRequest(@NotNull Boolean decisionsAboutMe, @NotNull Boolean moderationQueue) {
}
