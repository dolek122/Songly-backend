package com.songly.analytics;

public enum AnalyticsEventType {

	USER_REGISTERED,

	POST_LIKED,

	POST_COMMENTED,

	POST_SHARED,

	FRIEND_REQUEST_SENT,

	FRIEND_REQUEST_ACCEPTED,

	COMMUNITY_MEMBER_JOINED,

	/**
	 * <b>Posty zgłoszone po raz pierwszy</b>, nie liczba zgłoszeń — i ta różnica
	 * jest celowa (ADR 0019). {@code PostReportedEvent} powstaje tylko przy
	 * pierwszym zgłoszeniu danego posta, bo bramka anty-spamowa siedzi u
	 * publikującego (ADR 0012), a przeniesienie jej do konsumentów zduplikowałoby
	 * ją w dwóch miejscach. Licznik odpowiada więc na pytanie „ile treści budzi
	 * sprzeciw", a nie „ile razy kliknięto zgłoś"; tę drugą liczbę i tak zna tabela
	 * {@code post_reports}, która ma {@code created_at}.
	 */
	POST_FIRST_REPORTED,

	/** Zgłoszenia odrzucone przez moderację — post zostaje. */
	POST_REPORT_DISMISSED,

	/** Posty usunięte przez moderatora; usunięcia własnych postów tu nie ma. */
	COMMUNITY_POST_REMOVED,

	/**
	 * Rozdzielone na bezterminowe i czasowe, bo to jedyny sposób sprawdzenia, czy
	 * bany czasowe (ADR 0014) w ogóle się przyjęły — analityka liczy same typy, bez
	 * wymiarów (ADR 0004). Suma obu serii to wszystkie nowe bany.
	 */
	COMMUNITY_BAN_ISSUED_PERMANENT,

	COMMUNITY_BAN_ISSUED_TEMPORARY,

	/**
	 * Odwołania (ADR 0017). Zestawione z liczbą banów mówią, czy kanał jest w ogóle
	 * używany; {@code ACCEPTED} do {@code SUBMITTED} mówi, jak często moderacja
	 * sama uznaje, że się pomyliła — a to jest miara, której do tej pory nie było
	 * skąd wziąć.
	 */
	BAN_APPEAL_SUBMITTED,

	BAN_APPEAL_ACCEPTED,

	BAN_APPEAL_REJECTED
}
