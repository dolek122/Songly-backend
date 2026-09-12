package com.songly.identity;

/**
 * Stan odwołania od zawieszenia konta (ADR 0023). Brak odwołania to
 * {@code null}, nie osobna wartość — „można się odwołać" wynika z samego
 * istnienia aktywnego zawieszenia bez odwołania.
 *
 * <p>
 * {@code ACCEPTED} nie istnieje: uznanie odwołania to zdjęcie zawieszenia, więc
 * stan znika razem ze sprawą (ta sama konstrukcja co {@code BanAppealStatus} w
 * module community, ADR 0017).
 *
 * <p>
 * Osobny enum, a nie tamten: {@code community} zależy od {@code identity}, więc
 * zależność w drugą stronę dałaby cykl, który łapie reguła 2
 * {@code ModuleBoundariesTest}. To jedyny powód tego powtórzenia.
 */
public enum SuspensionAppealStatus {
	PENDING, REJECTED
}
