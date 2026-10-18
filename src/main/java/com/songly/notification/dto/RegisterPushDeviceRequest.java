package com.songly.notification.dto;

import com.songly.notification.PushPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Token FCM urządzenia (ADR 0040). Limit z zapasem — dzisiejsze tokeny mają
 * ~160 znaków.
 */
public record RegisterPushDeviceRequest(@NotBlank @Size(max = 512) String token, @NotNull PushPlatform platform) {
}
