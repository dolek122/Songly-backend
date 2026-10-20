package com.songly.common.mail;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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

	/** Ile czekamy przy zamykaniu aplikacji na wiadomości już przyjęte. */
	private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

	private final MailSender delegate;
	private final ThreadPoolExecutor executor;

	AsyncMailSender(MailSender delegate, int threads, int queueCapacity) {
		this.delegate = delegate;
		this.executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(queueCapacity), new MailThreadFactory(),
				new ThreadPoolExecutor.AbortPolicy());
	}

	@Override
	public void send(String to, String subject, String body) {
		Map<String, String> context = MDC.getCopyOfContextMap();
		try {
			executor.execute(() -> deliver(to, subject, body, context));
		} catch (RejectedExecutionException ex) {
			throw new MailDeliveryException("Kolejka wysyłki e-maili jest pełna", ex);
		}
	}

	private void deliver(String to, String subject, String body, Map<String, String> context) {
		if (context != null) {
			MDC.setContextMap(context);
		}
		try {
			delegate.send(to, subject, body);
		} catch (RuntimeException ex) {
			log.error("Nie udało się wysłać wiadomości e-mail", ex);
		} finally {
			MDC.clear();
		}
	}

	/**
	 * Wołane przez Springa przy zamykaniu kontekstu (wywnioskowana metoda niszcząca
	 * beana). Wiadomości przyjęte przed zamknięciem dostają chwilę na wyjście —
	 * reszta przepada z wpisem w logu.
	 */
	@Override
	public void close() throws InterruptedException {
		executor.shutdown();
		if (!executor.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
			int dropped = executor.shutdownNow().size();
			log.warn("Zamknięcie aplikacji przerwało wysyłkę e-maili, niewysłanych: {}", dropped);
		}
	}

	/**
	 * Wątki demony z czytelną nazwą: demon nie zatrzyma JVM, gdyby zamknięcie
	 * kontekstu się nie wydarzyło, a nazwa mówi w zrzucie wątków, co tu robią.
	 */
	private static final class MailThreadFactory implements ThreadFactory {

		private final AtomicInteger counter = new AtomicInteger();

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "mail-sender-" + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		}
	}
}
