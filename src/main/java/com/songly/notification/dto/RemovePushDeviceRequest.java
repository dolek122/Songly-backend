package com.songly.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RemovePushDeviceRequest(@NotBlank @Size(max = 512) String token) {
}
