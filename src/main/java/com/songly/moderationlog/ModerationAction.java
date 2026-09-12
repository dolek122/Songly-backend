package com.songly.moderationlog;

/**
 * Rodzaj zapisanej decyzji (ADR 0024). Wartości opisują <b>zdarzenia</b>, nie
 * stan: dziennik odpowiada na „co się stało i kiedy", a nie „jak jest teraz" —
 * stan trzymają tabele sankcji i ich własne endpointy.
 */
public enum ModerationAction {

	/** {@code expiresAt} niesie termin; {@code null} = ban bezterminowy. */
	COMMUNITY_BAN_ISSUED,

	/**
	 * Ban zdjęty ręcznie. Wygaśnięcie terminu wpisu nie tworzy — patrz ADR 0024.
	 */
	COMMUNITY_BAN_LIFTED,

	/**
	 * {@code reportCount} niesie liczbę zgłoszeń, które post miał w chwili
	 * usunięcia.
	 */
	COMMUNITY_POST_REMOVED,

	POST_REPORT_DISMISSED,

	/**
	 * Zgłoszenie <b>uwzględnione</b>: post zniknął, więc zgłaszający miał rację
	 * (ADR 0027). Wpis powstaje per zgłaszający, obok jednego
	 * {@link #COMMUNITY_POST_REMOVED} opisującego tę samą decyzję od strony autora
	 * — bez tego dziennik wiedział, ile zgłoszeń post miał, ale nie czyje.
	 */
	POST_REPORT_UPHELD,

	COMMUNITY_BAN_APPEAL_SUBMITTED,

	COMMUNITY_BAN_APPEAL_ACCEPTED,

	COMMUNITY_BAN_APPEAL_REJECTED,

	COMMUNITY_MODERATOR_GRANTED,

	COMMUNITY_MODERATOR_REVOKED,

	/** Decyzje platformowe — bez {@code communityId} (ADR 0022, 0023). */
	ACCOUNT_SUSPENDED,

	ACCOUNT_SUSPENSION_LIFTED,

	SUSPENSION_APPEAL_SUBMITTED,

	SUSPENSION_APPEAL_ACCEPTED,

	SUSPENSION_APPEAL_REJECTED
}
