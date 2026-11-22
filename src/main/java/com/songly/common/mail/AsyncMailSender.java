package com.songly.common.mail;

import com.songly.common.concurrent.BoundedExecutor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wysyłka poza wątkiem żądania (ADR 0041). Odpowiedź HTTP nie czeka na serwer
 * pocztowy.
 *
 * <p>
 * <b>Chodzi o czas odpowiedzi, a nie o wygodę.</b> Reset hasła wysyła maila
 * wyłącznie wtedy, gdy konto istnieje, a wysyłka SMTP trwa setki milisekund.
 * Przy wysyłce w wątku żądania sam czas 202 mówił więc, czy pod adresem jest
 * konto — dokładnie ta wyrocznia, której ten endpoint unika kodem odpowiedzi
 * (ADR 0008). Drugi powód to awaria serwera pocztowego: zawieszone połączenie
 * trzymało wątek żądania, a tych jest skończenie wiele.
 *
 * <p>
 * Kolejka ma granicę. Przepełniona rzuca {@link MailDeliveryException}, który
 * wołający już łapią (wiadomość przepada z wpisem w logu). Zablokowanie wątku
 * żądania przy przepełnieniu przywróciłoby oba problemy naraz.
 *
 * <p>
 * Kontekst MDC wędruje z żądaniem do wątku wysyłki, więc błąd wysyłki ma w logu
 * ten sam {@code requestId} co żądanie, które go spowodowało — bez adresu
 * odbiorcy (Claude.md §13).
 */
class AsyncMailSender implements MailSender, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(AsyncMailSender.class);

	private final MailSender delegate;
	private final BoundedExecutor executor;

	AsyncMailSender(MailSender delegate, int threads, int queueCapacity) {
		this.delegate = delegate;
		this.executor = new BoundedExecutor("mail-sender", threads, queueCapacity);
	}

	@Override
	public void send(String to, String subject, String body) {
		try {
			executor.execute(() -> deliver(to, subject, body));
		} catch (RejectedExecutionException ex) {
			throw new MailDeliveryException("Kolejka wysyłki e-maili jest pełna", ex);
		}
	}

	private void deliver(String to, String subject, String body) {
		try {
			delegate.send(to, subject, body);
		} catch (RuntimeException ex) {
			log.error("Nie udało się wysłać wiadomości e-mail", ex);
		}
	}

	/**
	 * Wołane przez Springa przy zamykaniu kontekstu (wywnioskowana metoda niszcząca
	 * beana). Wiadomości przyjęte przed zamknięciem dostają chwilę na wyjście —
	 * reszta przepada z wpisem w logu.
	 */
	@Override
	public void close() throws InterruptedException {
		executor.close();
	}
}
