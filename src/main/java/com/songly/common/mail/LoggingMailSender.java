package com.songly.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domyślna implementacja dla dev i testów: wypisuje wiadomość do logu zamiast
 * ją wysyłać.
 *
 * <p>
 * Nie jest to atrapa „żeby coś było" — bez niej lokalne przejście ścieżki
 * resetu hasła wymagałoby prawdziwego serwera SMTP, więc pierwszą rzeczą, którą
 * ktoś by zrobił, byłoby zaglądanie po token do bazy. Log z pełną treścią
 * wiadomości daje ten sam efekt bez uczenia nikogo obchodzenia własnego
 * mechanizmu.
 *
 * <p>
 * Loguje <b>całą treść, razem z tokenem</b> — i właśnie dlatego jest
 * przeznaczona wyłącznie dla środowisk deweloperskich. Na produkcji z
 * {@code provider=log} każdy z dostępem do logów mógłby przejmować konta.
 */
class LoggingMailSender implements MailSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

	private final String from;

	LoggingMailSender(String from) {
		this.from = from;
	}

	@Override
	public void send(String to, String subject, String body) {
		log.info("[MAIL:log] od={} do={} temat={}\n{}", from, to, subject, body);
	}
}
