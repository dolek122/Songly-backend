package com.songly.identity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Powód jest <b>obowiązkowy</b>, w odróżnieniu od powodu banu w społeczności
 * (ADR 0011): zawieszenie odcina konto od całej usługi, więc decyzja bez
 * uzasadnienia byłaby nie do odtworzenia ani dla innego administratora, ani dla
 * zawieszonego — a on ten powód zobaczy (ADR 0022).
 *
 * <p>
 * {@code durationDays} {@code null} = bezterminowo, jak przy banach czasowych
 * (ADR 0014).
 */
public record SuspendAccountRequest(@NotBlank @Size(max = 500) String reason, @Min(1) Integer durationDays) {
}
