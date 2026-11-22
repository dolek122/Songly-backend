package com.songly.common.concurrent;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class BoundedExecutor implements Executor, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(BoundedExecutor.class);

	private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

	private final String name;
	private final ThreadPoolExecutor executor;

	public BoundedExecutor(String name, int threads, int queueCapacity) {
		this.name = name;
		this.executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(queueCapacity), new NamedDaemonThreadFactory(name),
				new ThreadPoolExecutor.AbortPolicy());
	}

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

	@Override
	public void close() throws InterruptedException {
		executor.shutdown();
		if (!executor.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
			int dropped = executor.shutdownNow().size();
			log.warn("Zamknięcie aplikacji przerwało zadania w tle ({}), niewykonanych: {}", name, dropped);
		}
	}

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
