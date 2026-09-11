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
	POST_REPORT_DISMISSED,

	/**
	 * Do wszystkich moderatorów, gdy zbanowany złoży odwołanie (ADR 0017); zasobem
	 * jest społeczność. Co najwyżej raz na ban — odwołanie jest jedno.
	 */
	BAN_APPEAL_SUBMITTED,

	/** Do odwołującego: odwołanie uznane, ban zdjęty. Zasobem jest społeczność. */
	BAN_APPEAL_ACCEPTED,

	/**
	 * Do odwołującego: odwołanie odrzucone, ban zostaje. Zasobem jest społeczność.
	 */
	BAN_APPEAL_REJECTED,

	/** Do nowego moderatora; zasobem jest społeczność. */
	COMMUNITY_MODERATOR_GRANTED,

	/**
	 * Do byłego moderatora; zasobem jest społeczność. Bez tego odebranie roli
	 * objawiałoby się wyłącznie 403-kami na kolejce zgłoszeń — czyli decyzja
	 * wyglądałaby jak awaria, dokładnie problem, od którego zaczął się ADR 0012.
	 */
	COMMUNITY_MODERATOR_REVOKED;

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
	 *
	 * <p>
	 * Kryterium przynależności — nie „powiadomienie uznane za ważne", tylko jedno z
	 * dwóch: decyzja <b>ograniczająca</b> adresata, której nie może odrzucić (ban,
	 * usunięcie posta, odpowiedź na własną petycję — zgłoszenie czy odwołanie, ADR
	 * 0017: nie można odmówić odpowiedzi, o którą się prosiło), albo wiadomość
	 * <b>uruchamiająca pętlę moderacji</b> (zgłoszenie, odwołanie do rozpatrzenia)
	 * — gdyby ta podlegała blokadom, moderator blokujący trolla przestawałby
	 * widzieć jego sprawy i kanał umierał po cichu. Dlatego zmiany roli moderatora
	 * (ADR 0016) są tu <b>celowo nieobecne</b>: nadanie i odebranie uprawnień nie
	 * ogranicza zwykłego uczestnictwa, więc kto zablokował właściciela, temu wolno
	 * nie widzieć od niego także tego — koszt ponosi sam.
	 */
	static final Set<NotificationType> MODERATION = Set.of(COMMUNITY_BANNED, COMMUNITY_POST_REMOVED,
			COMMUNITY_POST_REPORTED, POST_REPORT_DISMISSED, BAN_APPEAL_SUBMITTED, BAN_APPEAL_ACCEPTED,
			BAN_APPEAL_REJECTED);
}
