package com.songly.identity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SuspendAccountRequest(@NotBlank @Size(max = 500) String reason,
		@Min(1) @Max(36_500) Integer durationDays) {
}
