package com.songly.identity;

import com.songly.common.mail.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Wiadomość z linkiem do potwierdzenia usunięcia konta (ADR 0043).
 * {@code AFTER_COMMIT} i łykanie błędu wysyłki z tych samych powodów co w
 * {@link PasswordResetMailer}: link musi prowadzić do tokenu, który naprawdę
 * jest w bazie, a błąd poczty nie może zamienić się w odpowiedź wyróżniającą
 * istniejące konta.
 */
@Component
class AccountDeletionMailer {

	private static final Logger log = LoggerFactory.getLogger(AccountDeletionMailer.class);

	private static final String SUBJECT = "Songly — potwierdź usunięcie konta";

	private final MailSender mailSender;
	private final AccountDeletionProperties properties;

	AccountDeletionMailer(MailSender mailSender, AccountDeletionProperties properties) {
		this.mailSender = mailSender;
		this.properties = properties;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onAccountDeletionLinkRequested(AccountDeletionLinkRequestedEvent event) {
		String link = properties.getConfirmUrl().replace("{token}", event.token());
		try {
			mailSender.send(event.email(), SUBJECT, """
					Cześć!

					Otrzymaliśmy prośbę o usunięcie Twojego konta w Songly. Żeby ją
					potwierdzić, otwórz poniższy link — zobaczysz stronę z przyciskiem,
					który usuwa konto na stałe:

					%s

					Samo otwarcie linku niczego nie usuwa. Link jest ważny przez %d min.

					Usunięcia nie da się cofnąć: znikną profil, zdjęcia, posty, komentarze,
					znajomości i społeczności, których jesteś właścicielem.

					Jeśli to nie Ty prosiłeś o usunięcie konta — zignoruj tę wiadomość.
					Bez kliknięcia przycisku na stronie nic się nie stanie.

					Zespół Songly""".formatted(link, properties.getTtl().toMinutes()));
		} catch (RuntimeException ex) {
			log.error("Nie udało się wysłać linku usunięcia konta {}", event.userId(), ex);
		}
	}
}
