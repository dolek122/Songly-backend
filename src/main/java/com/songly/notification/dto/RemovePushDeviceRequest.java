package com.songly.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Token w ciele, nie w ścieżce: tokeny FCM zawierają {@code :} i są długie, a
 * ścieżka z identyfikatorem urządzenia trafiałaby do logów dostępowych.
 */
public record RemovePushDeviceRequest(@NotBlank @Size(max = 512) String token) {
}
