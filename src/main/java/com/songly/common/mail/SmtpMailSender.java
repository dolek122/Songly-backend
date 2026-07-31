package com.songly.common.mail;

import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Wysyłka przez SMTP skonfigurowane w {@code spring.mail.*}.
 *
 * <p>
 * Wiadomość jest zwykłym tekstem, nie HTML-em. To nie jest tymczasowe
 * uproszczenie: e-mail transakcyjny z linkiem nie potrzebuje układu
 * graficznego, a wersja tekstowa przechodzi przez filtry antyspamowe lepiej niż
 * HTML z jednym przyciskiem. Gdy pojawi się potrzeba brandingu, dojdzie druga
 * część wiadomości, nie zamiana tej.
 */
class SmtpMailSender implements MailSender {

	private final JavaMailSender javaMailSender;
	private final String from;

	SmtpMailSender(JavaMailSender javaMailSender, String from) {
		this.javaMailSender = javaMailSender;
		this.from = from;
	}

	@Override
	public void send(String to, String subject, String body) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(to);
		message.setSubject(subject);
		message.setText(body);
		try {
			javaMailSender.send(message);
		} catch (MailException ex) {
			// Bez adresata w komunikacie — ten wyjątek trafi do logu u wołającego, a
			// adresy e-mail celowo w logach nie lądują (Claude.md §9).
			throw new MailDeliveryException("Nie udało się wysłać wiadomości e-mail", ex);
		}
	}
}
