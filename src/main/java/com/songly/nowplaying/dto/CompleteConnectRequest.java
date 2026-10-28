package com.songly.nowplaying.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Bilet z przekierowania {@code songly://now-playing/spotify?result=authorized}
 * (ADR 0050). Jednorazowy, ważny kilka minut i tylko dla konta, które zaczęło
 * łączenie.
 */
public record CompleteConnectRequest(@NotBlank @Size(max = 100) String ticket) {
}
