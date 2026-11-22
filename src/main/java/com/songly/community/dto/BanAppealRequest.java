package com.songly.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BanAppealRequest(@NotBlank @Size(max = 1000) String text) {
}
