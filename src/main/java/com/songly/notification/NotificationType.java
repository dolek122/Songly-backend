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

	/**
	 * Do autora komentarza usuniętego przez moderację; zasobem jest post, pod
	 * którym komentarz był — ten, w przeciwieństwie do usuniętego posta, istnieje.
	 */
	COMMUNITY_COMMENT_REMOVED,

	/** Do moderatorów, przy pierwszym zgłoszeniu danego posta. */
	COMMUNITY_POST_REPORTED,

	/**
	 * Druga instancja odwoławcza (ADR 0028): rozstrzygnięcie administracji
	 * platformy w sprawie banu społecznościowego. Zasobem jest społeczność.
	 */
	BAN_ESCALATION_ACCEPTED,

	BAN_ESCALATION_REJECTED,

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
			COMMUNITY_COMMENT_REMOVED, COMMUNITY_POST_REPORTED, POST_REPORT_DISMISSED, BAN_APPEAL_SUBMITTED,
			BAN_APPEAL_ACCEPTED, BAN_APPEAL_REJECTED, BAN_ESCALATION_ACCEPTED, BAN_ESCALATION_REJECTED);

	/**
	 * Decyzje, których aktorem jest moderator albo administrator rozstrzygający w
	 * sprawie adresata. Skrzynka <b>nie wydaje</b> przy nich nazwy ani awatara
	 * aktora: decyzja należy do społeczności (albo platformy), a nie do osoby,
	 * która kliknęła (ADR 0015) — wskazanie palcem służy odwetowi, nie zrozumieniu.
	 * Push i mail już podpisywały się społecznością; skrzynka wydawała dotąd nazwę,
	 * a ukrywał ją dopiero klient, więc każdy nowy typ, którego klient jeszcze nie
	 * znał, pokazałby moderatora z imienia.
	 *
	 * <p>
	 * Identyfikator aktora zostaje, bo kontrakt z klientem ma to pole jako
	 * wymagane.
	 */
	static final Set<NotificationType> DECIDED_BY_MODERATION = Set.of(COMMUNITY_BANNED, COMMUNITY_POST_REMOVED,
			COMMUNITY_COMMENT_REMOVED, POST_REPORT_DISMISSED, BAN_APPEAL_ACCEPTED, BAN_APPEAL_REJECTED,
			BAN_ESCALATION_ACCEPTED, BAN_ESCALATION_REJECTED);

	/**
	 * Powiadomienia o <b>stanie</b>, który aktor może cofnąć i przywrócić (ADR
	 * 0035): lajk, udostępnienie, członkostwo. Powstają raz na trójkę (adresat,
	 * aktor, zasób), niezależnie od liczby cykli cofnij/przywróć.
	 *
	 * <p>
	 * Bez tego para {@code DELETE}/{@code PUT} w pętli była generatorem powiadomień
	 * w cudzej skrzynce — każde przywrócenie stanu jest dla modułu content nowym
	 * zdarzeniem, bo z jego punktu widzenia nim jest. Dla adresata drugi lajk tej
	 * samej osoby pod tym samym postem nie niesie żadnej nowej informacji, a wie to
	 * tylko skrzynka, która pamięta, co już pokazała. Dlatego reguła siedzi tutaj,
	 * a nie u publikującego zdarzenie.
	 *
	 * <p>
	 * Komentarzy tu nie ma: każdy komentarz to nowa treść, nie przywrócony stan.
	 */
	static final Set<NotificationType> ONCE_PER_RESOURCE = Set.of(POST_LIKED, POST_SHARED, COMMUNITY_MEMBER_JOINED);

	/**
	 * Powiadomienia zwijane do jednego, <b>dopóki adresat go nie przeczytał</b>
	 * (ADR 0035). Kolejne odświeża istniejące (nowy zasób, nowa data) zamiast
	 * dokładać drugie.
	 *
	 * <p>
	 * Zaproszenie nie może iść regułą {@link #ONCE_PER_RESOURCE}: każde wysłanie to
	 * nowy wiersz z nowym identyfikatorem, więc „raz na zasób" nie łapałoby pętli
	 * wyślij/anuluj. „Raz na parę na zawsze" byłoby z kolei za mocne — były
	 * znajomy, który po roku zaprasza ponownie, to prawdziwa wiadomość. Zwijanie do
	 * przeczytania daje jedno i drugie: pętla dokłada najwyżej jedno powiadomienie
	 * na każde zajrzenie ofiary do skrzynki, a powiadomienie zawsze wskazuje
	 * zaproszenie, które faktycznie czeka.
	 */
	static final Set<NotificationType> COLLAPSED_WHILE_UNREAD = Set.of(FRIEND_REQUEST_RECEIVED);
}
