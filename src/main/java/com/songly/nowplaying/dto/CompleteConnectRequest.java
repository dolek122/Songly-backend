package com.songly.nowplaying.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompleteConnectRequest(@NotBlank @Size(max = 100) String ticket) {
}
