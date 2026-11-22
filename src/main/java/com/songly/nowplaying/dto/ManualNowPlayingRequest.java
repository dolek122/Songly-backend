package com.songly.nowplaying.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ManualNowPlayingRequest(@NotBlank @Size(max = 200) String title, @Size(max = 200) String artist,
		@Size(max = 200) String album, @Size(max = 60) String source) {
}
