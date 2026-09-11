package com.songly.community;

/**
 * Stan odwołania od bana (ADR 0017). Brak odwołania to {@code null} na
 * encji/DTO, nie osobna wartość — stan „można się odwołać" wynika z samego
 * istnienia aktywnego bana bez odwołania.
 *
 * <p>
 * {@code ACCEPTED} celowo nie istnieje: uznanie odwołania to unban, więc wiersz
 * bana znika razem ze sprawą, a odwołujący dowiaduje się z powiadomienia.
 *
 * <p>
 * Publiczny jak {@link CommunityRole}, bo występuje w DTO — ale żaden inny
 * moduł nie ma prawa go dotykać (lekcja z ADR 0016: zdarzenia niosą fakty, nie
 * słownik tego modułu).
 */
public enum BanAppealStatus {
	PENDING, REJECTED
}
