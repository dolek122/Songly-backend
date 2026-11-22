package com.songly.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SuspensionAppealRequest(@NotBlank @Size(max = 1000) String text) {
}
