package com.songly.notification;

import java.util.Set;

public enum NotificationType {

	POST_LIKED,

	POST_COMMENTED,

	POST_SHARED,

	FRIEND_REQUEST_RECEIVED,

	FRIEND_REQUEST_ACCEPTED,

	COMMUNITY_MEMBER_JOINED,

	/** Do zbanowanego; zasobem jest społeczność. */
	COMMUNITY_BANNED,

	/**
	 * Do autora usuniętego posta; zasobem jest społeczność, bo posta już nie ma.
	 */
	COMMUNITY_POST_REMOVED,

	/** Do moderatorów, przy pierwszym zgłoszeniu danego posta. */
	COMMUNITY_POST_REPORTED,

	/** Do zgłaszającego, gdy moderator uznał, że post zostaje. */
	POST_REPORT_DISMISSED;

	/**
	 * Powiadomienia o <b>decyzjach moderacyjnych</b>, w odróżnieniu od powiadomień
	 * o cudzej aktywności.
	 *
	 * <p>
	 * Różnica nie jest kosmetyczna. Reszta skrzynki znika, gdy nadawca jest
	 * zablokowany w którymkolwiek kierunku (Claude.md §9), i tak ma być — blokada
	 * odcina od zaczepek. Ale decyzja moderacyjna nie jest zaczepką i nie da się
	 * jej odmówić: gdyby podlegała temu filtrowi, wystarczyłoby zablokować
	 * moderatora, żeby nigdy nie zobaczyć informacji o własnym banie albo o
	 * usunięciu własnego posta. Blokada działałaby wtedy na szkodę blokującego,
	 * ukrywając przed nim dokładnie to, co powinien wiedzieć.
	 */
	static final Set<NotificationType> MODERATION = Set.of(COMMUNITY_BANNED, COMMUNITY_POST_REMOVED,
			COMMUNITY_POST_REPORTED, POST_REPORT_DISMISSED);
}
