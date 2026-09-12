package com.songly.content;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.content.report")
class ReportProperties {

	private int limit = 20;

	private Duration window = Duration.ofHours(1);

	private int dismissalPenalty = 5;

	private int minLimit = 1;

	private Duration historyWindow = Duration.ofDays(90);

	/**
	 * Ile zgłoszeń może złożyć <b>jedna osoba</b> w oknie {@link #getWindow()}.
	 *
	 * <p>
	 * Bramki „jedno zgłoszenie na parę (post, zgłaszający)" i „powiadamiamy tylko
	 * przy pierwszym zgłoszeniu posta" (ADR 0012) liczą <b>per post</b> — żadna nie
	 * ogranicza jednej osoby w poprzek wielu postów. Bez tego limitu jeden
	 * użytkownik zapychał kolejkę moderacji setką zgłoszeń, a od ADR 0013 także
	 * wysyłał tyle samo maili do każdego moderatora.
	 *
	 * <p>
	 * Domyślne 20/h jest celowo wysokie: ma odciąć zasypywanie, a nie sumiennego
	 * człowieka zgłaszającego serię spamu podczas nalotu.
	 */
	int getLimit() {
		return limit;
	}

	void setLimit(int limit) {
		this.limit = limit;
	}

	/**
	 * Okno <b>przesuwane</b> (liczymy zgłoszenia młodsze niż {@code now - window}),
	 * nie stałe jak w {@code RateLimitingFilter} — tam licznik siedzi w Redisie i
	 * okno wynika z TTL, tu liczymy wiersze, które i tak mają {@code created_at}.
	 * Przy okazji znika efekt progu na granicy okna.
	 */
	Duration getWindow() {
		return window;
	}

	void setWindow(Duration window) {
		this.window = window;
	}

	/**
	 * Ile „sztuk" limitu kosztuje jedno <b>odrzucone</b> zgłoszenie (ADR 0025).
	 * Przy domyślnych 20 i karze 5 wychodzi: bez odrzuceń 20/h, po jednym 15, po
	 * dwóch 10, po trzech 5, potem {@link #getMinLimit()}.
	 *
	 * <p>
	 * To jest realizacja „wagi zgłoszenia zależnej od historii" z ADR 0018 —
	 * jedynego rozwiązania, które tamten ADR uznał za prawdziwe lekarstwo na
	 * zasypywanie kolejki. Kara rośnie liniowo, nie wykładniczo: odrzucenie jest
	 * oceną moderatora, nie dowodem złej woli, więc nie powinno zjeżdżać w
	 * wyciszenie po dwóch pomyłkach.
	 */
	int getDismissalPenalty() {
		return dismissalPenalty;
	}

	void setDismissalPenalty(int dismissalPenalty) {
		this.dismissalPenalty = dismissalPenalty;
	}

	/**
	 * Podłoga limitu — <b>nigdy zero</b>, i to jest warunek, nie ostrożność.
	 * Zgłaszanie wyłączone na stałe oznaczałoby, że wystarczy kilka razy odrzucić
	 * czyjeś zgłoszenia, żeby odebrać mu narzędzie, którym może zgłosić nadużycie
	 * samego moderatora. Jedno zgłoszenie na okno zostaje zawsze.
	 */
	int getMinLimit() {
		return minLimit;
	}

	void setMinLimit(int minLimit) {
		this.minLimit = minLimit;
	}

	/**
	 * Jak długo rozstrzygnięte zgłoszenie liczy się do historii zgłaszającego (ADR
	 * 0030). Liczą się decyzje młodsze niż {@code now - historyWindow}, zarówno
	 * odrzucenia (kara), jak i trafienia (wybaczanie 1:1) — obie strony bilansu
	 * starzeją się razem.
	 *
	 * <p>
	 * <b>To nie jest {@link #getWindow()}.</b> Tamto okno mierzy natężenie („ile
	 * zgłoszeń w ciągu godziny"), to mierzy pamięć („jak długo pomyłka obniża
	 * przydział"). Są o dwa rzędy wielkości różne i o dwóch różnych rzeczach:
	 * pomieszanie ich dałoby albo limit bez pamięci, albo karę na godzinę.
	 *
	 * <p>
	 * Domyślne 90 dni to kompromis: dość długo, żeby zasypywanie kolejki nie
	 * opłacało się przez przeczekanie, i dość krótko, żeby czyjeś pomyłki z
	 * zeszłego roku nie zostały z nim na zawsze. Kara zaczyna odpuszczać sama, bez
	 * żadnego procesu w tle — to ta sama leniwość co przy wygasaniu banów (§8).
	 */
	Duration getHistoryWindow() {
		return historyWindow;
	}

	void setHistoryWindow(Duration historyWindow) {
		this.historyWindow = historyWindow;
	}
}
