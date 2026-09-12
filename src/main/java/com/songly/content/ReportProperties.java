package com.songly.content;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.content.report")
class ReportProperties {

	private int limit = 20;

	private Duration window = Duration.ofHours(1);

	private int dismissalPenalty = 5;

	private int minLimit = 1;

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
}
