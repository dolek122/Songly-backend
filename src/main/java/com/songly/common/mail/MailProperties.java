package com.songly.common.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Wyłącznie ustawienia <b>kanału</b>. Wszystko, co dotyczy treści albo tego,
 * czego dana wiadomość dotyczy (np. adres formularza resetu hasła), należy do
 * modułu domenowego, który tę wiadomość wysyła — inaczej {@code common} zaczyna
 * wiedzieć o domenie (Claude.md §3, reguła 3).
 */
@ConfigurationProperties(prefix = "songly.mail")
class MailProperties {

	/**
	 * {@code log} albo {@code smtp}. Przełącznik jest jawny, a nie wywnioskowany z
	 * obecności {@code spring.mail.host}: „czy ta instancja naprawdę wysyła maile"
	 * to za poważne pytanie, żeby odpowiedź na nie zależała od tego, czy ktoś
	 * pamiętał ustawić inną propercję.
	 *
	 * <p>
	 * Domyślnie {@code log}, bo domyślne środowisko to dev — instancja, która przez
	 * pomyłkę wyśle prawdziwego maila, jest gorsza niż taka, która przez pomyłkę
	 * tylko go zaloguje.
	 */
	private String provider = "log";

	private String from = "no-reply@songly.io";

	String getProvider() {
		return provider;
	}

	void setProvider(String provider) {
		this.provider = provider;
	}

	String getFrom() {
		return from;
	}

	void setFrom(String from) {
		this.from = from;
	}
}
