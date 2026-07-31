package com.songly.identity;

import com.songly.common.mail.MailSender;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Składa i wysyła wiadomość z linkiem resetującym.
 *
 * <p>
 * {@code AFTER_COMMIT}, a nie zwykły {@code @EventListener} — odwrotnie niż
 * listenery kasowania konta (Claude.md §9), które muszą zdążyć <i>przed</i>
 * commitem. Tu zależy nam dokładnie na czymś przeciwnym: mail ma wyjść wtedy i
 * tylko wtedy, gdy token naprawdę wylądował w bazie. Wysłany wcześniej
 * prowadziłby do linku, którego po wycofaniu transakcji nikt by nie honorował.
 */
@Component
class PasswordResetMailer {

	private static final Logger log = LoggerFactory.getLogger(PasswordResetMailer.class);

	private static final String SUBJECT = "Songly — reset hasła";

	private final MailSender mailSender;
	private final PasswordResetProperties properties;

	PasswordResetMailer(MailSender mailSender, PasswordResetProperties properties) {
		this.mailSender = mailSender;
		this.properties = properties;
	}

	/**
	 * Błąd wysyłki <b>nie</b> leci dalej. Powód nie jest wygodą: wyjątek na tym
	 * etapie zamieniłby odpowiedź w 500 — ale tylko dla adresów, pod którymi
	 * naprawdę istnieje konto, bo dla nieistniejących nie ma czego wysyłać. Awaria
	 * serwera pocztowego stałaby się więc tą samą wyrocznią, której cały ten
	 * przepływ starannie unika. Token jest już zapisany, więc użytkownik może
	 * ponowić żądanie, a my mamy wpis w logu i alert z metryk HTTP.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPasswordResetRequested(PasswordResetRequestedEvent event) {
		try {
			mailSender.send(event.email(), SUBJECT, body(event.token()));
		} catch (RuntimeException ex) {
			log.error("Nie udało się wysłać maila z resetem hasła dla konta {}", event.userId(), ex);
		}
	}

	/**
	 * Wiadomość mówi wprost, co zrobić, gdy resetu nie zamawiał adresat — to jedyny
	 * sygnał, po którym użytkownik może się zorientować, że ktoś próbuje przejąć
	 * jego konto.
	 */
	private String body(String token) {
		String link = properties.getResetUrl().replace("{token}", token);
		return """
				Cześć!

				Otrzymaliśmy prośbę o zresetowanie hasła do Twojego konta w Songly.
				Ustaw nowe hasło, korzystając z poniższego linku:

				%s

				Link jest jednorazowy i wygasa za %s. Zresetowanie hasła wyloguje Cię
				ze wszystkich urządzeń.

				Jeśli to nie Ty prosiłeś o reset — zignoruj tę wiadomość. Twoje hasło
				pozostaje bez zmian, a link straci ważność sam z siebie.

				Zespół Songly""".formatted(link, humanReadable(properties.getTtl()));
	}

	private String humanReadable(Duration ttl) {
		long minutes = ttl.toMinutes();
		if (minutes > 0 && minutes % 60 == 0) {
			long hours = minutes / 60;
			return hours + " " + plural(hours, "godzinę", "godziny", "godzin");
		}
		return minutes + " " + plural(minutes, "minutę", "minuty", "minut");
	}

	/**
	 * Polska odmiana przez liczebnik. Warunek na 12–14 nie jest przesadą — bez
	 * niego wychodzi „13 minuty", bo końcówka jest ta sama co w „3 minuty".
	 */
	private static String plural(long count, String one, String few, String many) {
		if (count == 1) {
			return one;
		}
		long lastTwo = count % 100;
		long last = count % 10;
		return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? few : many;
	}
}
