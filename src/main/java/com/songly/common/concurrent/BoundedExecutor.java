package com.songly.common.concurrent;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;
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
 * Pula do rozmów z serwisami zewnętrznymi (SMTP, FCM) poza wątkiem żądania (ADR
 * 0041, 0051). Wątek żądania nie może czekać na cudzy serwer: trzyma wtedy
 * wątek Tomcata, a z listenera {@code AFTER_COMMIT} także połączenia z bazą.
 *
 * <p>
 * <b>Kolejka ma granicę i przy przepełnieniu {@link #execute} rzuca
 * {@link RejectedExecutionException}.</b> Wołający decyduje, co wtedy przepada;
 * zablokowanie wątku żądania przywróciłoby dokładnie to, przed czym ta klasa
 * chroni.
 *
 * <p>
 * Kontekst MDC wędruje z żądaniem do wątku w tle, więc błąd ma w logu ten sam
 * {@code requestId} co żądanie, które go spowodowało. Wyjątek z zadania kończy
 * się wpisem w logu, nie śmiercią wątku.
 */
public final class BoundedExecutor implements Executor, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(BoundedExecutor.class);

	/** Ile czekamy przy zamykaniu aplikacji na zadania już przyjęte. */
	private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

	private final String name;
	private final ThreadPoolExecutor executor;

	public BoundedExecutor(String name, int threads, int queueCapacity) {
		this.name = name;
		this.executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(queueCapacity), new NamedDaemonThreadFactory(name),
				new ThreadPoolExecutor.AbortPolicy());
	}

	/**
	 * @throws RejectedExecutionException
	 *             gdy kolejka jest pełna albo pula już zamknięta
	 */
	@Override
	public void execute(Runnable task) {
		Map<String, String> context = MDC.getCopyOfContextMap();
		executor.execute(() -> run(task, context));
	}

	private void run(Runnable task, Map<String, String> context) {
		if (context != null) {
			MDC.setContextMap(context);
		}
		try {
			task.run();
		} catch (RuntimeException ex) {
			log.error("Zadanie w tle ({}) zakończyło się błędem", name, ex);
		} finally {
			MDC.clear();
		}
	}

	/**
	 * Zadania przyjęte przed zamknięciem dostają chwilę na wykonanie, reszta
	 * przepada z wpisem w logu.
	 */
	@Override
	public void close() throws InterruptedException {
		executor.shutdown();
		if (!executor.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
			int dropped = executor.shutdownNow().size();
			log.warn("Zamknięcie aplikacji przerwało zadania w tle ({}), niewykonanych: {}", name, dropped);
		}
	}

	/**
	 * Wątki demony z czytelną nazwą: demon nie zatrzyma JVM, gdyby zamknięcie
	 * kontekstu się nie wydarzyło, a nazwa mówi w zrzucie wątków, co tu robią.
	 */
	private static final class NamedDaemonThreadFactory implements ThreadFactory {

		private final String prefix;
		private final AtomicInteger counter = new AtomicInteger();

		private NamedDaemonThreadFactory(String prefix) {
			this.prefix = prefix;
		}

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		}
	}
}
