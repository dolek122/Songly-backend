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

	/** Cudzy komentarz usunięty przez moderację; podmiotem jest jego autor. */
	COMMUNITY_COMMENT_REMOVED,

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

	/**
	 * Druga instancja (ADR 0028): sprawa przekazana administracji platformy, a
	 * potem jej rozstrzygnięcie. {@code ESCALATION_ACCEPTED} to ban uchylony przez
	 * kogoś z zewnątrz społeczności — jedyny taki przypadek w tym dzienniku.
	 */
	COMMUNITY_BAN_APPEAL_ESCALATED,

	COMMUNITY_BAN_ESCALATION_ACCEPTED,

	COMMUNITY_BAN_ESCALATION_REJECTED,

	COMMUNITY_MODERATOR_GRANTED,

	COMMUNITY_MODERATOR_REVOKED,

	/** Decyzje platformowe — bez {@code communityId} (ADR 0022, 0023). */
	ACCOUNT_SUSPENDED,

	ACCOUNT_SUSPENSION_LIFTED,

	SUSPENSION_APPEAL_SUBMITTED,

	SUSPENSION_APPEAL_ACCEPTED,

	SUSPENSION_APPEAL_REJECTED,

	/**
	 * Nadanie i odebranie roli administratora platformy (ADR 0031) — jedyna decyzja
	 * w tym dzienniku, która nie ogranicza podmiotu, a go <b>uprawnia</b>.
	 *
	 * <p>
	 * Jest tu, bo dziennik odpowiada na pytanie „kto komu co zrobił i kiedy", a
	 * nadanie władzy nad wszystkimi sankcjami z tej listy jest najcięższą decyzją,
	 * jaką da się na tej platformie podjąć. Dotąd zostawiała po sobie wyłącznie
	 * linijkę {@code WARN} w logach i przyrost licznika — czyli ślad wymagający
	 * dostępu do maszyny, w odróżnieniu od każdej sankcji wobec zwykłego konta.
	 */
	PLATFORM_ADMIN_GRANTED,

	PLATFORM_ADMIN_REVOKED
}
