package com.songly.content;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.content.report")
class ReportProperties {

	private int limit = 20;

	private Duration window = Duration.ofHours(1);

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
}
