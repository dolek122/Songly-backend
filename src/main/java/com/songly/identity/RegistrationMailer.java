package com.songly.identity;

import com.songly.common.mail.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Dwie wiadomości, które może wywołać jedno żądanie rejestracji — i to, która z
 * nich pójdzie, jest jedyną różnicą między adresem wolnym a zajętym. Sama
 * odpowiedź HTTP jest w obu przypadkach identyczna (202), bo inaczej endpoint
 * byłby listą istniejących kont.
 *
 * <p>
 * Obie wysyłki są {@code AFTER_COMMIT} i obie łykają błąd wysyłki, dokładnie z
 * tego samego powodu co {@link PasswordResetMailer}: wyjątek zamieniłby
 * odpowiedź w 500, a 500 zdarzałoby się tylko w jednym z dwóch przypadków —
 * czyli awaria poczty odtworzyłaby wyrocznię, którą reszta tego przepływu
 * likwiduje.
 */
@Component
class RegistrationMailer {

	private static final Logger log = LoggerFactory.getLogger(RegistrationMailer.class);

	private static final String VERIFICATION_SUBJECT = "Songly — potwierdź swój adres e-mail";

	private static final String EXISTING_ACCOUNT_SUBJECT = "Songly — ktoś próbował założyć konto na Twój adres";

	private final MailSender mailSender;
	private final RegistrationProperties properties;

	RegistrationMailer(MailSender mailSender, RegistrationProperties properties) {
		this.mailSender = mailSender;
		this.properties = properties;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onVerificationRequested(RegistrationVerificationRequestedEvent event) {
		String link = properties.getVerificationUrl().replace("{token}", event.token());
		send(event.email(), VERIFICATION_SUBJECT, """
				Cześć!

				Ktoś (mamy nadzieję, że Ty) zakłada konto w Songly na ten adres.
				Potwierdź go, żeby dokończyć rejestrację:

				%s

				Link wygasa po %d h. Do czasu potwierdzenia konto nie istnieje — jeśli
				to nie Ty, nie musisz robić nic, a my nie zapiszemy o Tobie niczego
				poza tym jednym zgłoszeniem, które samo wygaśnie.

				Zespół Songly""".formatted(link, properties.getTtl().toHours()), null);
	}

	/**
	 * Wiadomość do właściciela istniejącego konta. Kieruje go do <b>resetu
	 * hasła</b>, a nie do rejestracji: jeśli to on sam zapomniał, że ma już konto,
	 * to właśnie resetu potrzebuje, a jeśli to ktoś obcy — reset jest jedyną
	 * sensowną reakcją, którą i tak sam kontroluje.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onRegistrationOnExistingAccount(RegistrationAttemptedOnExistingAccountEvent event) {
		send(event.email(), EXISTING_ACCOUNT_SUBJECT, """
				Cześć!

				Ktoś właśnie próbował założyć konto w Songly na ten adres — ale konto
				już tu istnieje, więc nie utworzyliśmy niczego nowego.

				Jeśli to byłeś Ty i nie pamiętasz hasła, użyj opcji „nie pamiętam
				hasła" w aplikacji. Jeśli to nie Ty — nic nie musisz robić. Nikt
				postronny nie dowiedział się od nas, że masz tu konto, i nikt nie
				uzyskał do niego dostępu.

				Zespół Songly""", event.userId());
	}

	private void send(String recipient, String subject, String body, Object accountId) {
		try {
			mailSender.send(recipient, subject, body);
		} catch (RuntimeException ex) {
			// Bez adresu e-mail w logu (Claude.md §9). Przy zgłoszeniu na wolny adres
			// nie mamy nawet czym go zastąpić — i tak nie ma jeszcze konta.
			log.error("Nie udało się wysłać wiadomości rejestracyjnej, konto: {}", accountId, ex);
		}
	}
}
