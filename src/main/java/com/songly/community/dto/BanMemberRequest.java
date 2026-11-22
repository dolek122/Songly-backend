package com.songly.community.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record BanMemberRequest(@Size(max = 500) String reason, @Min(1) @Max(36_500) Integer durationDays) {
}
