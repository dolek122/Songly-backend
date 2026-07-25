package com.songly.nowplaying.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateNowPlayingSettingsRequest(@NotNull Boolean shareWithFriends) {
}
